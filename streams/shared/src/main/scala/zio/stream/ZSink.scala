/*
 * Copyright 2017-2019 John A. De Goes and the ZIO Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package zio.stream

import zio._
import zio.clock.Clock
import zio.duration.Duration

import scala.collection.mutable

/**
 * A `Sink[E, A0, A, B]` consumes values of type `A`, ultimately producing
 * either an error of type `E`, or a value of type `B` together with a remainder
 * of type `A0`.
 *
 * Sinks form monads and combine in the usual ways.
 */
trait ZSink[-R, +E, A, +B] { self =>

  type State

  /**
   * Decides whether the Sink should continue from the current state.
   */
  def cont(state: State): Boolean

  /**
   * Produces a final value of type `B` along with a remainder of type `Chunk[A0]`.
   */
  def extract(state: State): ZIO[R, E, (B, Chunk[A])]

  /**
   * The initial state of the sink.
   */
  def initial: ZIO[R, E, State]

  /**
   * Steps through one iteration of the sink.
   */
  def step(state: State, a: A): ZIO[R, E, State]

  /**
   * Operator alias for `zipRight`
   */
  final def *>[R1 <: R, E1 >: E, C](
    that: ZSink[R1, E1, A, C]
  ): ZSink[R1, E1, A, C] =
    zip(that).map(_._2)

  /**
   * Operator alias for `zipLeft`
   */
  final def <*[R1 <: R, E1 >: E, C](
    that: ZSink[R1, E1, A, C]
  ): ZSink[R1, E1, A, B] =
    zip(that).map(_._1)

  /**
   * Operator alias for `zip`
   */
  final def <*>[R1 <: R, E1 >: E, C](
    that: ZSink[R1, E1, A, C]
  ): ZSink[R1, E1, A, (B, C)] =
    self zip that

  /**
   * Operator alias for `orElse` for two sinks consuming and producing values of the same type.
   */
  final def <|[R1 <: R, E1, B1 >: B](
    that: ZSink[R1, E1, A, B1]
  ): ZSink[R1, E1, A, B1] =
    (self orElse that).map(_.merge)

  final def optional = ?

  final def ? : ZSink[R, Nothing, A, Option[B]] =
    new ZSink[R, Nothing, A, Option[B]] {
      import ZSink.internal._

      type State = Optional[self.State, A]

      val initial = self.initial.fold(
        _ => Optional.Fail(Chunk.empty),
        s =>
          if (self.cont(s)) Optional.More(s)
          else Optional.Done(s)
      )

      def step(state: State, a: A) =
        state match {
          case Optional.More(s1) =>
            self
              .step(s1, a)
              .fold(
                _ => Optional.Fail(Chunk.single(a)),
                s2 =>
                  if (self.cont(s2)) Optional.More(s2)
                  else Optional.Done(s2)
              )

          case s => UIO.succeed(s)
        }

      def extract(state: State) =
        state match {
          case Optional.Done(s) =>
            self
              .extract(s)
              .fold(
                _ => (None, Chunk.empty), { case (b, as) => (Some(b), as) }
              )

          case Optional.More(s) =>
            self
              .extract(s)
              .fold(
                _ => (None, Chunk.empty), { case (b, as) => (Some(b), as) }
              )

          case Optional.Fail(as) => UIO.succeed((None, as))
        }

      def cont(state: State) =
        state match {
          case Optional.More(_) => true
          case _                => false
        }
    }

  /**
   * A named alias for `race`.
   */
  final def |[R1 <: R, E1 >: E, B1 >: B](
    that: ZSink[R1, E1, A, B1]
  ): ZSink[R1, E1, A, B1] =
    self.race(that)

  /**
   * Creates a sink that always produces `c`
   */
  final def as[C](c: => C): ZSink[R, E, A, C] = self.map(_ => c)

  /**
   * Replaces any error produced by this sink.
   */
  final def asError[E1](e1: E1): ZSink[R, E1, A, B] = self.mapError(_ => e1)

  final def chunked: ZSink[R, E, Chunk[A], B] =
    new ZSink[R, E, Chunk[A], B] {
      type State = (self.State, Chunk[A])
      val initial = self.initial.map((_, Chunk.empty))
      def step(state: State, a: Chunk[A]) =
        self.stepChunkSlice(state._1, a).map { case (s, chunk) => (s, chunk) }
      def extract(state: State) = self.extract(state._1).map { case (b, leftover) => (b, Chunk(leftover, state._2)) }
      def cont(state: State)    = self.cont(state._1)
    }

  final def collectAll: ZSink[R, E, A, List[B]] =
    collectAllWith[List[B]](List.empty[B])((bs, b) => b :: bs).map(_.reverse)

  final def collectAllN(i: Int): ZSink[R, E, A, List[B]] =
    collectAllWith[(List[B], Int)]((Nil, 0)) {
      case ((bs, len), b) =>
        (b :: bs, len + 1)
    }.untilOutput {
      case (_, len) =>
        len >= i
    }.map(_.getOrElse((Nil, 0))._1.reverse)

  final def collectAllWith[S](
    z: S
  )(f: (S, B) => S): ZSink[R, E, A, S] =
    collectAllWhileWith(_ => true)(z)(f)

  final def collectAllWhile(
    p: A => Boolean
  ): ZSink[R, E, A, List[B]] =
    collectAllWhileWith(p)(List.empty[B])((bs, b) => b :: bs)
      .map(_.reverse)

  final def collectAllWhileWith[S](
    p: A => Boolean
  )(z: S)(f: (S, B) => S): ZSink[R, E, A, S] =
    new ZSink[R, E, A, S] {
      type State = (S, self.State, Boolean, Chunk[A])

      val initial = self.initial.map(s => (z, s, true, Chunk.empty))

      def step(state: State, a: A) =
        if (!p(a))
          self.extract(state._2).map {
            case (b, leftover) =>
              (f(state._1, b), state._2, false, leftover ++ Chunk.single(a))
          } else
          self.step(state._2, a).flatMap { s1 =>
            if (self.cont(s1)) UIO.succeed((state._1, s1, true, Chunk.empty))
            else
              self.extract(s1).flatMap {
                case (b, as) =>
                  self.initial.flatMap { init =>
                    self.stepChunk(init, as).map(s2 => (f(state._1, b), s2, self.cont(s2), Chunk.empty))
                  }
              }
          }

      def extract(state: State) = UIO.succeed((state._1, state._4))

      def cont(state: State) = state._3
    }

  /**
   * Drops all elements entering the sink for as long as the specified predicate
   * evaluates to `true`.
   */
  final def dropWhile(pred: A => Boolean): ZSink[R, E, A, B] =
    new ZSink[R, E, A, B] {
      type State = (self.State, Boolean)

      val initial = self.initial.map((_, true))

      def step(state: State, a: A) =
        if (!state._2) self.step(state._1, a).map((_, false))
        else {
          if (pred(a)) UIO.succeed(state)
          else self.step(state._1, a).map((_, false))
        }

      def extract(state: State) = self.extract(state._1)

      def cont(state: State) = self.cont(state._1)
    }

  /**
   * Creates a sink producing values of type `C` obtained by each produced value of type `B`
   * transformed into a sink by `f`.
   */
  final def flatMap[R1 <: R, E1 >: E, C](
    f: B => ZSink[R1, E1, A, C]
  ): ZSink[R1, E1, A, C] =
    new ZSink[R1, E1, A, C] {
      type State = Either[self.State, (ZSink[R1, E1, A, C], Any, Chunk[A])]

      val initial = self.initial.flatMap { init =>
        if (self.cont(init)) UIO.succeed((Left(init)))
        else
          self.extract(init).flatMap {
            case (b, leftover) =>
              val that = f(b)
              that.initial.flatMap { s1 =>
                that.stepChunkSlice(s1, leftover).map {
                  case (s2, chunk) =>
                    Right((that, s2, chunk))
                }
              }
          }
      }

      def step(state: State, a: A) =
        state match {
          case Left(s1) =>
            self.step(s1, a).flatMap { s2 =>
              if (self.cont(s2)) UIO.succeed(Left(s2))
              else
                self.extract(s2).flatMap {
                  case (b, leftover) =>
                    val that = f(b)
                    that.initial.flatMap { init =>
                      that.stepChunkSlice(init, leftover).map {
                        case (s3, chunk) =>
                          Right((that, s3, chunk))
                      }
                    }
                }
            }

          // If `that` needs to continue, it will have already processed all of the
          // leftovers from `self`, because they were stepped in `initial` or `case Left` above.
          case Right((that, s1, _)) =>
            that.step(s1.asInstanceOf[that.State], a).map(s2 => Right((that, s2, Chunk.empty)))
        }

      def extract(state: State) =
        state match {
          case Left(s1) =>
            self.extract(s1).flatMap {
              case (b, leftover) =>
                val that = f(b)
                that.initial.flatMap { init =>
                  that.stepChunkSlice(init, leftover).flatMap {
                    case (s2, chunk) =>
                      that.extract(s2).map {
                        case (c, cLeftover) =>
                          (c, cLeftover ++ chunk)
                      }
                  }
                }
            }

          case Right((that, s2, chunk)) =>
            that.extract(s2.asInstanceOf[that.State]).map {
              case (c, leftover) =>
                (c, leftover ++ chunk)
            }
        }

      def cont(state: State) =
        state match {
          case Left(s1)             => self.cont(s1)
          case Right((that, s2, _)) => that.cont(s2.asInstanceOf[that.State])
        }
    }

  /**
   * Filters the inputs fed to this sink.
   */
  def filter(f: A => Boolean): ZSink[R, E, A, B] =
    new ZSink[R, E, A, B] {
      type State = self.State
      val initial                  = self.initial
      def step(state: State, a: A) = if (f(a)) self.step(state, a) else UIO.succeed(state)
      def extract(state: State)    = self.extract(state)
      def cont(state: State)       = self.cont(state)
    }

  /**
   * Effectfully filters the inputs fed to this sink.
   */
  final def filterM[R1 <: R, E1 >: E](f: A => ZIO[R1, E1, Boolean]): ZSink[R1, E1, A, B] =
    new ZSink[R1, E1, A, B] {
      type State = self.State
      val initial = self.initial

      def step(state: State, a: A) = f(a).flatMap { b =>
        if (b) self.step(state, a)
        else UIO.succeed(state)
      }

      def extract(state: State) = self.extract(state)
      def cont(state: State)    = self.cont(state)
    }

  /**
   * Filters this sink by the specified predicate, dropping all elements for
   * which the predicate evaluates to true.
   */
  final def filterNot(f: A => Boolean): ZSink[R, E, A, B] =
    filter(a => !f(a))

  /**
   * Effectfully filters this sink by the specified predicate, dropping all elements for
   * which the predicate evaluates to true.
   */
  final def filterNotM[R1 <: R, E1 >: E](f: A => ZIO[R1, E1, Boolean]): ZSink[R1, E1, A, B] =
    filterM(a => f(a).map(!_))

  /**
   * Runs `n` sinks in parallel, where `n` is the number of possible keys
   * generated by `f`.
   */
  final def keyed[K](f: A => K): ZSink[R, E, A, Map[K, B]] =
    new ZSink[R, E, A, Map[K, B]] {
      type State = Map[K, self.State]

      val initial =
        self.initial.map(init => Map.empty[K, self.State].withDefaultValue(init))

      def step(state: State, a: A) = {
        val k = f(a)
        self.step(state(k), a).map(s => state + (k -> s))
      }

      def extract(state: State) =
        ZIO
          .foreach(state.toList) {
            case (k, s) => self.extract(s).map(k -> _)
          }
          .map { list =>
            val results   = list.map { case (k, (b, _)) => (k, b) }.toMap
            val leftovers = Chunk.fromIterable(list.map(_._2._2)).flatten
            (results, leftovers)
          }

      def cont(state: State) = state.values.forall(self.cont)
    }

  /**
   * Maps the value produced by this sink.
   */
  def map[C](f: B => C): ZSink[R, E, A, C] =
    new ZSink[R, E, A, C] {
      type State = self.State
      val initial                  = self.initial
      def step(state: State, a: A) = self.step(state, a)
      def extract(state: State)    = self.extract(state).map { case (b, leftover) => (f(b), leftover) }
      def cont(state: State)       = self.cont(state)
    }

  /**
   * Maps any error produced by this sink.
   */
  final def mapError[E1](f: E => E1): ZSink[R, E1, A, B] =
    new ZSink[R, E1, A, B] {
      type State = self.State
      val initial                  = self.initial.mapError(f)
      def step(state: State, a: A) = self.step(state, a).mapError(f)
      def extract(state: State)    = self.extract(state).mapError(f)
      def cont(state: State)       = self.cont(state)
    }

  final def mapInput[C](f: C => A)(g: A => C): ZSink[R, E, C, B] =
    new ZSink[R, E, C, B] {
      type State = self.State
      val initial                  = self.initial
      def step(state: State, c: C) = self.step(state, f(c))
      def extract(state: State) = self.extract(state).map {
        case (b, leftover) => (b, leftover.map(g))
      }
      def cont(state: State) = self.cont(state)
    }

  final def mapInputM[R1 <: R, R2 <: R1, E1 >: E, E2 >: E1, C](
    f: C => ZIO[R1, E1, A]
  )(g: A => ZIO[R2, E2, C]): ZSink[R2, E2, C, B] =
    new ZSink[R2, E2, C, B] {
      type State = self.State
      val initial                  = self.initial
      def step(state: State, c: C) = f(c).flatMap(self.step(state, _))
      def extract(state: State) = self.extract(state).flatMap {
        case (b, leftover) => leftover.mapM(g).map(b -> _)
      }
      def cont(state: State) = self.cont(state)
    }

  /**
   * Effectfully maps the value produced by this sink.
   */
  final def mapM[R1 <: R, E1 >: E, C](f: B => ZIO[R1, E1, C]): ZSink[R1, E1, A, C] =
    new ZSink[R1, E1, A, C] {
      type State = self.State
      val initial                  = self.initial
      def step(state: State, a: A) = self.step(state, a)
      def extract(state: State)    = self.extract(state).flatMap { case (b, leftover) => f(b).map((_, leftover)) }
      def cont(state: State)       = self.cont(state)
    }

  /**
   * Runs both sinks in parallel on the same input. If the left one succeeds,
   * its value will be produced. Otherwise, whatever the right one produces
   * will be produced. If the right one succeeds before the left one, it
   * accumulates the full input until the left one fails, so it can return
   * it as the remainder. This allows this combinator to function like `choice`
   * in parser combinator libraries.
   *
   * Left:  ============== FAIL!
   * Right: ===== SUCCEEDS!
   *             xxxxxxxxx <- Should NOT be consumed
   */
  final def orElse[R1 <: R, E1, C](
    that: ZSink[R1, E1, A, C]
  ): ZSink[R1, E1, A, Either[B, C]] =
    new ZSink[R1, E1, A, Either[B, C]] {
      import ZSink.internal._

      type State = (Side[E, self.State, (B, Chunk[A])], Side[E1, that.State, (C, Chunk[A])])

      def decide(state: State): ZIO[R1, E1, State] =
        state match {
          case (Side.Error(_), Side.Error(e)) => IO.fail(e)
          case sides                          => UIO.succeed(sides)
        }

      val leftInit: ZIO[R, Nothing, Side[E, self.State, (B, Chunk[A])]] =
        self.initial.foldM(
          e => UIO.succeed(Side.Error(e)),
          s =>
            if (self.cont(s)) UIO.succeed(Side.State(s))
            else self.extract(s).fold(Side.Error(_), Side.Value(_))
        )

      val rightInit: ZIO[R1, Nothing, Side[E1, that.State, (C, Chunk[A])]] =
        that.initial.foldM(
          e => UIO.succeed(Side.Error(e)),
          s =>
            if (that.cont(s)) UIO.succeed(Side.State(s))
            else that.extract(s).fold(Side.Error(_), Side.Value(_))
        )

      val initial = leftInit.zipPar(rightInit).flatMap(decide(_))

      def step(state: State, a: A) = {
        val leftStep: ZIO[R, Nothing, Side[E, self.State, (B, Chunk[A])]] =
          state._1 match {
            case Side.State(s) =>
              self
                .step(s, a)
                .foldM(
                  e => UIO.succeed(Side.Error(e)),
                  s =>
                    if (self.cont(s)) UIO.succeed(Side.State(s))
                    else self.extract(s).fold(Side.Error(_), Side.Value(_))
                )

            case side => UIO.succeed(side)
          }

        val rightStep: ZIO[R1, Nothing, Side[E1, that.State, (C, Chunk[A])]] =
          state._2 match {
            case Side.State(s) =>
              that
                .step(s, a)
                .foldM(
                  e => UIO.succeed(Side.Error(e)),
                  s =>
                    if (that.cont(s)) UIO.succeed(Side.State(s))
                    else that.extract(s).fold(Side.Error(_), Side.Value(_))
                )

            case Side.Value((c, as)) =>
              val as1 = as ++ Chunk.single(a)
              UIO.succeed(Side.Value((c, as1)))

            case side => UIO.succeed(side)
          }

        leftStep.zipPar(rightStep).flatMap(decide(_))
      }

      def extract(state: State) =
        state match {
          case (Side.Error(_), Side.Error(e))             => IO.fail(e)
          case (Side.Error(_), Side.State(s))             => that.extract(s).map { case (c, leftover) => (Right(c), leftover) }
          case (Side.Error(_), Side.Value((c, leftover))) => UIO.succeed((Right(c), leftover))
          case (Side.Value((b, leftover)), _)             => UIO.succeed((Left(b), leftover))
          case (Side.State(s), Side.Error(e)) =>
            self.extract(s).map { case (b, leftover) => (Left(b), leftover) }.asError(e)
          case (Side.State(s), Side.Value((c, leftover))) =>
            self.extract(s).map { case (b, ll) => (Left(b), ll) }.catchAll(_ => UIO.succeed((Right(c), leftover)))
          case (Side.State(s1), Side.State(s2)) =>
            self
              .extract(s1)
              .map {
                case (b, leftover) =>
                  ((Left(b), leftover))
              }
              .catchAll(
                _ =>
                  that.extract(s2).map {
                    case (c, leftover) =>
                      ((Right(c), leftover))
                  }
              )
        }

      def cont(state: State) =
        state match {
          case (Side.Error(_), Side.State(s))   => that.cont(s)
          case (Side.Error(_), _)               => false
          case (Side.State(s1), Side.State(s2)) => self.cont(s1) || that.cont(s2)
          case (Side.State(s), _)               => self.cont(s)
          case (Side.Value(_), _)               => false
        }
    }

  /**
   * Narrows the environment by partially building it with `f`
   */
  final def provideSome[R1](f: R1 => R): ZSink[R1, E, A, B] =
    new ZSink[R1, E, A, B] {
      type State = self.State
      val initial                  = self.initial.provideSome(f)
      def step(state: State, a: A) = self.step(state, a).provideSome(f)
      def extract(state: State)    = self.extract(state).provideSome(f)
      def cont(state: State)       = self.cont(state)
    }

  /**
   * Runs both sinks in parallel on the input, returning the result from the
   * one that finishes successfully first.
   */
  final def race[R1 <: R, E1 >: E, B1 >: B](
    that: ZSink[R1, E1, A, B1]
  ): ZSink[R1, E1, A, B1] =
    self.raceBoth(that).map(_.merge)

  /**
   * Steps through a chunk of iterations of the sink
   */
  final def stepChunk(state: State, as: Chunk[A]): ZIO[R, E, State] = {
    val len = as.length

    def loop(state: State, i: Int): ZIO[R, E, State] =
      if (i >= len) UIO.succeed(state)
      else if (self.cont(state)) self.step(state, as(i)).flatMap(loop(_, i + 1))
      else UIO.succeed(state)

    loop(state, 0)
  }

  final def stepChunkSlice(state: State, as: Chunk[A]): ZIO[R, E, (State, Chunk[A])] = {
    val len = as.length

    def loop(state: State, i: Int): ZIO[R, E, (State, Chunk[A])] =
      if (i >= len) UIO.succeed(state -> Chunk.empty)
      else if (self.cont(state)) self.step(state, as(i)).flatMap(loop(_, i + 1))
      else UIO.succeed(state -> as.splitAt(i)._2)

    loop(state, 0)
  }

  /**
   * Runs both sinks in parallel on the input, returning the result from the
   * one that finishes successfully first.
   */
  final def raceBoth[R1 <: R, E1 >: E, C](
    that: ZSink[R1, E1, A, C]
  ): ZSink[R1, E1, A, Either[B, C]] =
    new ZSink[R1, E1, A, Either[B, C]] {
      import ZSink.internal._

      type State = (Side[E, self.State, (B, Chunk[A])], Side[E1, that.State, (C, Chunk[A])])

      def decide(state: State): ZIO[R1, E1, State] =
        state match {
          case (Side.Error(e1), Side.Error(e2)) => IO.halt(Cause.Both(Cause.fail(e1), Cause.fail(e2)))
          case sides                            => UIO.succeed(sides)
        }

      val leftInit: ZIO[R, Nothing, Side[E, self.State, (B, Chunk[A])]] =
        self.initial.foldM(
          e => UIO.succeed(Side.Error(e)),
          s =>
            if (self.cont(s)) UIO.succeed(Side.State(s))
            else self.extract(s).fold(Side.Error(_), Side.Value(_))
        )

      val rightInit: ZIO[R1, Nothing, Side[E1, that.State, (C, Chunk[A])]] =
        that.initial.foldM(
          e => UIO.succeed(Side.Error(e)),
          s =>
            if (that.cont(s)) UIO.succeed(Side.State(s))
            else that.extract(s).fold(Side.Error(_), Side.Value(_))
        )

      val initial = leftInit.zipPar(rightInit).flatMap(decide(_))

      def step(state: State, a: A) = {
        val leftStep: ZIO[R, Nothing, Side[E, self.State, (B, Chunk[A])]] =
          state._1 match {
            case Side.State(s) =>
              self
                .step(s, a)
                .foldM(
                  e => UIO.succeed(Side.Error(e)),
                  s =>
                    if (self.cont(s)) UIO.succeed(Side.State(s))
                    else self.extract(s).fold(Side.Error(_), Side.Value(_))
                )

            case side => UIO.succeed(side)
          }

        val rightStep: ZIO[R1, Nothing, Side[E1, that.State, (C, Chunk[A])]] =
          state._2 match {
            case Side.State(s) =>
              that
                .step(s, a)
                .foldM(
                  e => UIO.succeed(Side.Error(e)),
                  s =>
                    if (that.cont(s)) UIO.succeed(Side.State(s))
                    else that.extract(s).fold(Side.Error(_), Side.Value(_))
                )

            case side => UIO.succeed(side)
          }

        leftStep.zipPar(rightStep).flatMap(decide(_))
      }

      def extract(state: State) =
        state match {
          case (Side.Error(e1), Side.Error(e2))           => IO.halt(Cause.Both(Cause.fail(e1), Cause.fail(e2)))
          case (Side.Error(_), Side.State(s))             => that.extract(s).map { case (c, leftover) => (Right(c), leftover) }
          case (Side.Error(_), Side.Value((c, leftover))) => UIO.succeed((Right(c), leftover))
          case (Side.State(s), Side.Error(e)) =>
            self.extract(s).map { case (b, leftover) => (Left(b), leftover) }.asError(e)
          case (Side.State(s1), Side.State(s2)) =>
            self
              .extract(s1)
              .map {
                case (b, leftover) =>
                  (Left(b), leftover)
              }
              .catchAll(
                _ =>
                  that.extract(s2).map {
                    case (c, leftover) =>
                      (Right(c), leftover)
                  }
              )
          case (Side.State(_), Side.Value((c, leftover))) => UIO.succeed((Right(c), leftover))
          case (Side.Value((b, leftover)), _)             => UIO.succeed((Left(b), leftover))
        }

      def cont(state: State) =
        state match {
          case (Side.State(s1), Side.State(s2)) => self.cont(s1) && that.cont(s2)
          case _                                => false
        }
    }

  /**
   * Runs both sinks in parallel on the input and combines the results into a Tuple.
   */
  final def zipPar[R1 <: R, E1 >: E, C](
    that: ZSink[R1, E1, A, C]
  ): ZSink[R1, E1, A, (B, C)] =
    new ZSink[R1, E1, A, (B, C)] {
      type State = (Either[self.State, (B, Chunk[A])], Either[that.State, (C, Chunk[A])])

      val initial = self.initial.zipPar(that.initial).flatMap {
        case (s1, s2) =>
          val left  = if (self.cont(s1)) UIO.succeed(Left(s1)) else self.extract(s1).map(Right(_))
          val right = if (that.cont(s2)) UIO.succeed(Left(s2)) else that.extract(s2).map(Right(_))
          left.zipPar(right)
      }

      def step(state: State, a: A) = {
        val leftStep: ZIO[R, E, Either[self.State, (B, Chunk[A])]] =
          state._1.fold(
            s1 =>
              self.step(s1, a).flatMap { s2 =>
                if (self.cont(s2)) UIO.succeed(Left(s2))
                else self.extract(s2).map(Right(_))
              },
            { case (b, leftover) => UIO.succeed(Right((b, leftover ++ Chunk.single(a)))) }
          )

        val rightStep: ZIO[R1, E1, Either[that.State, (C, Chunk[A])]] =
          state._2.fold(
            s1 =>
              that.step(s1, a).flatMap { s2 =>
                if (that.cont(s2)) UIO.succeed(Left(s2))
                else that.extract(s2).map(Right(_))
              },
            { case (c, leftover) => UIO.succeed(Right((c, leftover ++ Chunk.single(a)))) }
          )

        leftStep.zipPar(rightStep)
      }

      def extract(state: State) = {
        val leftExtract  = state._1.fold(self.extract, UIO.succeed)
        val rightExtract = state._2.fold(that.extract, UIO.succeed)
        leftExtract.zipPar(rightExtract).map {
          case ((b, ll), (c, rl)) => ((b, c), List(ll, rl).minBy(_.length))
        }
      }

      def cont(state: State) =
        state match {
          case (Left(s1), Left(s2)) => self.cont(s1) || that.cont(s2)
          case (Left(s), Right(_))  => self.cont(s)
          case (Right(_), Left(s))  => that.cont(s)
          case (Right(_), Right(_)) => false
        }
    }

  /**
   * Times the invocation of the sink
   */
  final def timed: ZSink[R with Clock, E, A, (Duration, B)] =
    new ZSink[R with Clock, E, A, (Duration, B)] {
      type State = (Long, Long, self.State)

      val initial = for {
        s <- self.initial
        t <- zio.clock.nanoTime
      } yield (t, 0L, s)

      def step(state: State, a: A) =
        state match {
          case (t, total, st) =>
            for {
              s   <- self.step(st, a)
              now <- zio.clock.nanoTime
              t1  = now - t
            } yield (now, total + t1, s)
        }

      def extract(s: State) = self.extract(s._3).map {
        case (b, leftover) =>
          ((Duration.fromNanos(s._2), b), leftover)
      }

      def cont(state: State) = self.cont(state._3)
    }

  final def takeWhile(pred: A => Boolean): ZSink[R, E, A, B] =
    new ZSink[R, E, A, B] {
      type State = (self.State, Chunk[A])

      val initial = self.initial.map((_, Chunk.empty))

      def step(state: State, a: A) =
        if (pred(a)) self.step(state._1, a).map((_, Chunk.empty))
        else UIO.succeed((state._1, Chunk.single(a)))

      def extract(state: State) = self.extract(state._1).map { case (b, leftover) => (b, leftover ++ state._2) }

      def cont(state: State) = self.cont(state._1)
    }

  /**
   * Creates a sink that ignores all produced elements.
   */
  final def unit: ZSink[R, E, A, Unit] = as(())

  /**
   * Creates a sink that produces values until one verifies
   * the predicate `f`.
   */
  final def untilOutput(f: B => Boolean): ZSink[R, E, A, Option[B]] =
    new ZSink[R, E, A, Option[B]] {
      type State = (self.State, Option[B], Chunk[A])

      val initial = self.initial.flatMap { s =>
        self
          .extract(s)
          .fold(
            _ => (s, None, Chunk.empty),
            { case (b, leftover) => if (f(b)) (s, Some(b), leftover) else (s, None, Chunk.empty) }
          )
      }

      def step(state: State, a: A) =
        self
          .step(state._1, a)
          .flatMap { s =>
            self.extract(s).flatMap {
              case (b, leftover) =>
                if (f(b)) UIO.succeed((s, Some(b), leftover))
                else UIO.succeed((s, None, Chunk.empty))
            }
          }

      def extract(state: State) = UIO.succeed((state._2, state._3))

      def cont(state: State) = state._2.isEmpty
    }

  final def update(state: State): ZSink[R, E, A, B] =
    new ZSink[R, E, A, B] {
      type State = self.State
      val initial                  = IO.succeed(state)
      def step(state: State, a: A) = self.step(state, a)
      def extract(state: State)    = self.extract(state)
      def cont(state: State)       = self.cont(state)
    }

  /**
   * Runs two sinks in unison and matches produced values pair-wise.
   */
  final def zip[R1 <: R, E1 >: E, C](that: ZSink[R1, E1, A, C]): ZSink[R1, E1, A, (B, C)] =
    flatMap(b => that.map(c => (b, c)))

  /**
   * Runs two sinks in unison and keeps only values on the left.
   */
  final def zipLeft[R1 <: R, E1 >: E, C](that: ZSink[R1, E1, A, C]): ZSink[R1, E1, A, B] =
    self <* that

  /**
   * Runs two sinks in unison and keeps only values on the right.
   */
  final def zipRight[R1 <: R, E1 >: E, C](that: ZSink[R1, E1, A, C]): ZSink[R1, E1, A, C] =
    self *> that

  /**
   * Runs two sinks in unison and merges values pair-wise.
   */
  final def zipWith[R1 <: R, E1 >: E, C, D](that: ZSink[R1, E1, A, C])(f: (B, C) => D): ZSink[R1, E1, A, D] =
    zip(that).map(f.tupled)
}

object ZSink extends ZSinkPlatformSpecific {

  private[ZSink] object internal {
    sealed trait Side[+E, +S, +A]
    object Side {
      final case class Error[E](e: E) extends Side[E, Nothing, Nothing]
      final case class State[S](s: S) extends Side[Nothing, S, Nothing]
      final case class Value[A](a: A) extends Side[Nothing, Nothing, A]
    }

    sealed trait Optional[+S, +A]
    object Optional {
      final case class Done[S](s: S)         extends Optional[S, Nothing]
      final case class More[S](s: S)         extends Optional[S, Nothing]
      final case class Fail[A](as: Chunk[A]) extends Optional[Nothing, A]
    }

    final case class SplitLines(
      accumulatedLines: Chunk[String],
      concat: Option[String],
      wasSplitCRLF: Boolean,
      cont: Boolean,
      leftover: Chunk[String]
    )

    def assertNonNegative(n: Long): UIO[Unit] =
      if (n < 0) UIO.die(new NegativeArgument(s"Unexpected negative unit value `$n`"))
      else UIO.unit

    def assertPositive(n: Long): UIO[Unit] =
      if (n <= 0) UIO.die(new NonpositiveArgument(s"Unexpected nonpositive unit value `$n`"))
      else UIO.unit

    class NegativeArgument(message: String) extends IllegalArgumentException(message)

    class NonpositiveArgument(message: String) extends IllegalArgumentException(message)

    case class FoldWeightedState[S, A](s: S, cost: Long, cont: Boolean, leftovers: Chunk[A])
  }

  /**
   * Creates a sink that waits for a single value to be produced.
   */
  final def await[A]: ZSink[Any, Unit, A, A] = identity

  /**
   * Creates a sink accumulating incoming values into a list.
   */
  final def collectAll[A]: ZSink[Any, Nothing, A, List[A]] =
    foldLeft[A, List[A]](List.empty[A])((as, a) => a :: as).map(_.reverse)

  /**
   * Creates a sink accumulating incoming values into a list of maximum size `n`.
   */
  final def collectAllN[A](n: Long): ZSink[Any, Nothing, A, List[A]] =
    foldUntil[List[A], A](List.empty[A], n)((list, element) => element :: list).map(_.reverse)

  /**
   * Creates a sink accumulating incoming values into a set.
   */
  final def collectAllToSet[A]: ZSink[Any, Nothing, A, Set[A]] =
    foldLeft[A, Set[A]](Set.empty[A])((set, element) => set + element)

  /**
   * Creates a sink accumulating incoming values into a set of maximum size `n`.
   */
  final def collectAllToSetN[A](n: Long): ZSink[Any, Nothing, A, Set[A]] = {
    type State = (Set[A], Boolean)
    def f(state: State, a: A): (State, Chunk[A]) = {
      val newSet = state._1 + a
      if (newSet.size > n) ((state._1, false), Chunk.single(a))
      else if (newSet.size == n) ((newSet, false), Chunk.empty)
      else ((newSet, true), Chunk.empty)
    }
    fold[A, State]((Set.empty, true))(_._2)(f).map(_._1)
  }

  /**
   * Creates a sink accumulating incoming values into a map.
   * Key of each element is determined by supplied function.
   */
  final def collectAllToMap[K, A](key: A => K): ZSink[Any, Nothing, A, Map[K, A]] =
    foldLeft[A, Map[K, A]](Map.empty[K, A])((map, element) => map + (key(element) -> element))

  /**
   * Creates a sink accumulating incoming values into a map of maximum size `n`.
   * Key of each element is determined by supplied function.
   */
  final def collectAllToMapN[K, A](n: Long)(key: A => K): ZSink[Any, Nothing, A, Map[K, A]] = {
    type State = (Map[K, A], Boolean)
    def f(state: State, a: A): (State, Chunk[A]) = {
      val newMap = state._1 + (key(a) -> a)
      if (newMap.size > n) ((state._1, false), Chunk.single(a))
      else if (newMap.size == n) ((newMap, false), Chunk.empty)
      else ((newMap, true), Chunk.empty)
    }
    fold[A, State]((Map.empty, true))(_._2)(f).map(_._1)
  }

  /**
   * Accumulates incoming elements into a list as long as they verify predicate `p`.
   */
  final def collectAllWhile[A](p: A => Boolean): ZSink[Any, Nothing, A, List[A]] =
    fold[A, (List[A], Boolean)]((Nil, true))(_._2) {
      case ((as, _), a) =>
        if (p(a)) ((a :: as, true), Chunk.empty) else ((as, false), Chunk.single(a))
    }.map(_._1.reverse)

  /**
   * Accumulates incoming elements into a list as long as they verify effectful predicate `p`.
   */
  final def collectAllWhileM[R, E, A](p: A => ZIO[R, E, Boolean]): ZSink[R, E, A, List[A]] =
    foldM[R, E, A, (List[A], Boolean)]((Nil, true))(_._2) {
      case ((as, _), a) =>
        p(a).map(if (_) ((a :: as, true), Chunk.empty) else ((as, false), Chunk.single(a)))
    }.map(_._1.reverse)

  /**
   * Creates a sink halting with the specified `Throwable`.
   */
  final def die(e: Throwable): ZSink[Any, Nothing, Any, Nothing] =
    ZSink.halt(Cause.die(e))

  /**
   * Creates a sink halting with the specified message, wrapped in a
   * `RuntimeException`.
   */
  final def dieMessage(m: String): ZSink[Any, Nothing, Any, Nothing] =
    ZSink.halt(Cause.die(new RuntimeException(m)))

  /**
   * Creates a sink consuming all incoming values until completion.
   */
  final def drain: ZSink[Any, Nothing, Any, Unit] =
    foldLeft(())((s, _) => s)

  /**
   * Creates a sink failing with a value of type `E`.
   */
  final def fail[A, E](e: E): ZSink[Any, E, A, Nothing] =
    new SinkPure[E, A, Nothing] {
      type State = Unit
      val initialPure                  = ()
      def stepPure(state: State, a: A) = ()
      def extractPure(state: State)    = Left(e)
      def cont(state: State)           = false
    }

  /**
   * Creates a sink by folding over a structure of type `S`.
   */
  final def fold[A, S](
    z: S
  )(contFn: S => Boolean)(f: (S, A) => (S, Chunk[A])): ZSink[Any, Nothing, A, S] =
    new SinkPure[Nothing, A, S] {
      type State = (S, Chunk[A])
      val initialPure                  = (z, Chunk.empty)
      def stepPure(state: State, a: A) = f(state._1, a)
      def extractPure(state: State)    = Right(state)
      def cont(state: State)           = contFn(state._1)
    }

  /**
   * Creates a sink by folding over a structure of type `S`.
   */
  final def foldLeft[A, S](z: S)(f: (S, A) => S): ZSink[Any, Nothing, A, S] =
    fold[A, S](z)(_ => true)((s, a) => (f(s, a), Chunk.empty))

  /**
   * Creates a sink by effectully folding over a structure of type `S`.
   */
  final def foldLeftM[R, E, A, S](z: S)(f: (S, A) => ZIO[R, E, S]): ZSink[R, E, A, S] =
    foldM[R, E, A, S](z)(_ => true)((s, a) => f(s, a).map((_, Chunk.empty)))

  /**
   * Creates a sink by effectfully folding over a structure of type `S`.
   */
  final def foldM[R, E, A, S](
    z: S
  )(contFn: S => Boolean)(f: (S, A) => ZIO[R, E, (S, Chunk[A])]): ZSink[R, E, A, S] =
    new ZSink[R, E, A, S] {
      type State = (S, Chunk[A])
      val initial                  = UIO.succeed((z, Chunk.empty))
      def step(state: State, a: A) = f(state._1, a)
      def extract(state: State)    = UIO.succeed(state)
      def cont(state: State)       = contFn(state._1)
    }

  /**
   * Creates a sink that effectfully folds elements of type `A` into a structure
   * of type `S`, until `max` worth of elements (determined by the `costFn`) have
   * been folded.
   *
   * @note Elements that have an individual cost larger than `max` will
   * cause the stream to hang. See [[ZSink.foldWeightedDecomposeM]] for
   * a variant that can handle these.
   */
  final def foldWeightedM[R, R1 <: R, E, E1 >: E, A, S](
    z: S
  )(
    costFn: A => ZIO[R, E, Long],
    max: Long
  )(f: (S, A) => ZIO[R1, E1, S]): ZSink[R1, E1, A, S] =
    foldWeightedDecomposeM[R, R1, E1, E1, A, S](z)(costFn, max, (a: A) => UIO.succeed(Chunk.single(a)))(f)

  /**
   * Creates a sink that effectfully folds elements of type `A` into a structure
   * of type `S`, until `max` worth of elements (determined by the `costFn`) have
   * been folded.
   *
   * The `decompose` function will be used for decomposing elements that
   * cause an `S` aggregate to cross `max` into smaller elements. See
   * [[ZSink.foldWeightedDecompose]] for an example.
   */
  final def foldWeightedDecomposeM[R, R1 <: R, E, E1 >: E, A, S](
    z: S
  )(
    costFn: A => ZIO[R, E, Long],
    max: Long,
    decompose: A => ZIO[R, E, Chunk[A]]
  )(f: (S, A) => ZIO[R1, E1, S]): ZSink[R1, E1, A, S] =
    new ZSink[R1, E1, A, S] {
      import internal.FoldWeightedState

      type State = FoldWeightedState[S, A]

      val initial = UIO.succeed(FoldWeightedState[S, A](z, 0L, true, Chunk.empty))

      def step(state: State, a: A) =
        costFn(a).flatMap { cost =>
          val newCost = cost + state.cost

          if (newCost > max)
            decompose(a).map(leftovers => state.copy(cont = false, leftovers = state.leftovers ++ leftovers))
          else if (newCost == max)
            f(state.s, a).map(FoldWeightedState(_, newCost, false, Chunk.empty))
          else
            f(state.s, a).map(FoldWeightedState(_, newCost, true, Chunk.empty))
        }

      def extract(state: State) = UIO.succeed((state.s, state.leftovers))

      def cont(state: State) = state.cont
    }

  /**
   * Creates a sink that folds elements of type `A` into a structure
   * of type `S`, until `max` worth of elements (determined by the `costFn`)
   * have been folded.
   *
   * @note Elements that have an individual cost larger than `max` will
   * cause the stream to hang. See [[ZSink.foldWeightedDecompose]] for
   * a variant that can handle these.
   */
  final def foldWeighted[A, S](
    z: S
  )(costFn: A => Long, max: Long)(
    f: (S, A) => S
  ): ZSink[Any, Nothing, A, S] =
    foldWeightedDecompose(z)(costFn, max, (a: A) => Chunk.single(a))(f)

  /**
   * Creates a sink that folds elements of type `A` into a structure
   * of type `S`, until `max` worth of elements (determined by the `costFn`)
   * have been folded.
   *
   * The `decompose` function will be used for decomposing elements that
   * cause an `S` aggregate to cross `max` into smaller elements. For
   * example:
   * {{{
   * Stream(1, 5, 1)
   *  .transduce(
   *    Sink
   *      .foldWeightedDecompose(List[Int]())((i: Int) => i.toLong, 4,
   *        (i: Int) => Chunk(i - 1, 1)) { (acc, el) =>
   *        el :: acc
   *      }
   *      .map(_.reverse)
   *  )
   *  .runCollect
   * }}}
   *
   * The stream would emit the elements `List(1), List(4), List(1, 1)`.
   * The [[ZSink.foldWeightedDecomposeM]] allows the decompose function
   * to return a `ZIO` value, and consequently it allows the sink to fail.
   */
  final def foldWeightedDecompose[A, S](
    z: S
  )(costFn: A => Long, max: Long, decompose: A => Chunk[A])(
    f: (S, A) => S
  ): ZSink[Any, Nothing, A, S] =
    new SinkPure[Nothing, A, S] {
      import internal.FoldWeightedState

      type State = FoldWeightedState[S, A]

      val initialPure = FoldWeightedState[S, A](z, 0L, true, Chunk.empty)

      def stepPure(state: State, a: A) = {
        val newCost = costFn(a) + state.cost

        if (newCost > max)
          state.copy(cont = false, leftovers = state.leftovers ++ decompose(a))
        else if (newCost == max)
          FoldWeightedState(f(state.s, a), newCost, false, Chunk.empty)
        else
          FoldWeightedState(f(state.s, a), newCost, true, Chunk.empty)
      }

      def extractPure(state: State) = Right((state.s, state.leftovers))

      def cont(state: State) = state.cont
    }

  /**
   * Creates a sink that effectfully folds elements of type `A` into a structure
   * of type `S` until `max` elements have been folded.
   *
   * Like [[ZSink.foldWeightedM]], but with a constant cost function of 1.
   */
  final def foldUntilM[R, E, S, A](z: S, max: Long)(f: (S, A) => ZIO[R, E, S]): ZSink[R, E, A, S] =
    foldWeightedM[R, R, E, E, A, S](z)(_ => UIO.succeed(1), max)(f)

  /**
   * Creates a sink that folds elements of type `A` into a structure
   * of type `S` until `max` elements have been folded.
   *
   * Like [[ZSink.foldWeighted]], but with a constant cost function of 1.
   */
  final def foldUntil[S, A](z: S, max: Long)(f: (S, A) => S): ZSink[Any, Nothing, A, S] =
    foldWeighted[A, S](z)(_ => 1, max)(f)

  /**
   * Creates a single-value sink produced from an effect
   */
  final def fromEffect[R, E, B](b: => ZIO[R, E, B]): ZSink[R, E, Any, B] =
    new ZSink[R, E, Any, B] {
      type State = Unit
      val initial                    = IO.succeed(())
      def step(state: State, a: Any) = IO.succeed(())
      def extract(state: State)      = b.map((_, Chunk.empty))
      def cont(state: State)         = false
    }

  /**
   * Creates a sink that purely transforms incoming values.
   */
  final def fromFunction[A, B](f: A => B): ZSink[Any, Unit, A, B] =
    identity.map(f)

  /**
   * Creates a sink halting with a specified cause.
   */
  final def halt[E](e: Cause[E]): ZSink[Any, E, Any, Nothing] =
    new ZSink[Any, E, Any, Nothing] {
      type State = Unit
      val initial                    = UIO.succeed(())
      def step(state: State, a: Any) = UIO.succeed(())
      def extract(state: State)      = IO.halt(e)
      def cont(state: State)         = false
    }

  /**
   * Creates a sink by that merely passes on incoming values.
   */
  final def identity[A]: ZSink[Any, Unit, A, A] =
    new SinkPure[Unit, A, A] {
      type State = Option[A]
      val initialPure                  = None
      def stepPure(state: State, a: A) = Some(a)
      def extractPure(state: State)    = state.fold[Either[Unit, A]](Left(()))(a => Right(a)).map((_, Chunk.empty))
      def cont(state: State)           = state.isEmpty
    }

  /**
   * Creates a sink by starts consuming value as soon as one fails
   * the predicate `p`.
   */
  final def ignoreWhile[A](p: A => Boolean): ZSink[Any, Nothing, A, Unit] =
    ignoreWhileM(a => IO.succeed(p(a)))

  /**
   * Creates a sink by starts consuming value as soon as one fails
   * the effectful predicate `p`.
   */
  final def ignoreWhileM[R, E, A](p: A => ZIO[R, E, Boolean]): ZSink[R, E, A, Unit] =
    new ZSink[R, E, A, Unit] {
      type State = Chunk[A]
      val initial = IO.succeed(Chunk.empty)
      def step(state: State, a: A) =
        p(a).map(if (_) state else Chunk.single(a))
      def extract(state: State) = IO.succeed(((), state))
      def cont(state: State)    = state.isEmpty
    }

  /**
   * Returns a sink that must at least perform one extraction or else
   * will "fail" with `end`.
   */
  final def pull1[R, R1 <: R, E, A, B](
    end: ZIO[R1, E, B]
  )(input: A => ZSink[R, E, A, B]): ZSink[R1, E, A, B] =
    new ZSink[R1, E, A, B] {
      type State = Option[(ZSink[R1, E, A, B], Any)]

      val initial = IO.succeed(None)

      def step(state: State, a: A) =
        state match {
          case None =>
            val sink = input(a)
            sink.initial.map(s => Some((sink, s)))
          case Some((sink, state)) =>
            sink.step(state.asInstanceOf[sink.State], a).map(state => Some(sink -> state))
        }

      def extract(state: State) =
        state match {
          case None                => end.map((_, Chunk.empty))
          case Some((sink, state)) => sink.extract(state.asInstanceOf[sink.State])
        }

      def cont(state: State) = state.fold(true) {
        case ((sink, s)) => sink.cont(s.asInstanceOf[sink.State])
      }
    }

  /**
   * Creates a sink that consumes the first value verifying the predicate `p`
   * or fails as soon as the sink won't make any more progress.
   */
  final def read1[E, A](e: Option[A] => E)(p: A => Boolean): ZSink[Any, E, A, A] =
    new SinkPure[E, A, A] {
      type State = (Either[E, Option[A]], Chunk[A])

      val initialPure = (Right(None), Chunk.empty)

      def stepPure(state: State, a: A) =
        state match {
          case (Right(Some(_)), _) => (state._1, Chunk(a))
          case (Right(None), _) =>
            if (p(a)) (Right(Some(a)), Chunk.empty)
            else (Left(e(Some(a))), Chunk.single(a))
          case s => (s._1, Chunk.single(a))
        }

      def extractPure(state: State) =
        state match {
          case (Right(Some(a)), _) => Right((a, state._2))
          case (Right(None), _)    => Left(e(None))
          case (Left(e), _)        => Left(e)
        }

      def cont(state: State) =
        state match {
          case (Right(None), _) => true
          case _                => false
        }
    }

  /**
   * Splits strings on newlines. Handles both `\r\n` and `\n`.
   */
  final val splitLines: ZSink[Any, Nothing, String, Chunk[String]] =
    new SinkPure[Nothing, String, Chunk[String]] {
      import ZSink.internal._

      type State = SplitLines

      val initialPure = SplitLines(Chunk.empty, None, false, true, Chunk.empty)

      override def stepPure(state: State, a: String) = {
        val accumulatedLines = state.accumulatedLines
        val concat           = state.concat.getOrElse("") + a
        val wasSplitCRLF     = state.wasSplitCRLF

        if (concat.isEmpty) state
        else {
          val buf = mutable.ArrayBuffer[String]()

          var i =
            // If we had a split CRLF, we start reading from the last character of the
            // leftover (which was the '\r')
            if (wasSplitCRLF) state.concat.map(_.length).getOrElse(1) - 1
            // Otherwise we just skip over the entire previous leftover as it doesn't
            // contain a newline.
            else state.concat.map(_.length).getOrElse(0)

          var sliceStart = 0
          var splitCRLF  = false

          while (i < concat.length) {
            if (concat(i) == '\n') {
              buf += concat.substring(sliceStart, i)
              i += 1
              sliceStart = i
            } else if (concat(i) == '\r' && (i + 1 < concat.length) && (concat(i + 1) == '\n')) {
              buf += concat.substring(sliceStart, i)
              i += 2
              sliceStart = i
            } else if (concat(i) == '\r' && (i == concat.length - 1)) {
              splitCRLF = true
              i += 1
            } else {
              i += 1
            }
          }

          if (buf.isEmpty) SplitLines(accumulatedLines, Some(concat), splitCRLF, true, Chunk.empty)
          else {
            val newLines = Chunk.fromArray(buf.toArray[String])
            val leftover = concat.substring(sliceStart, concat.length)

            if (splitCRLF) SplitLines(accumulatedLines ++ newLines, Some(leftover), splitCRLF, true, Chunk.empty)
            else {
              val remainder = if (leftover.nonEmpty) Chunk.single(leftover) else Chunk.empty
              SplitLines(accumulatedLines ++ newLines, None, splitCRLF, false, remainder)
            }
          }
        }
      }

      override def extractPure(state: State) =
        Right((state.accumulatedLines ++ state.concat.map(Chunk.single(_)).getOrElse(Chunk.empty), state.leftover))

      def cont(state: State) = state.cont
    }

  /**
   * Merges chunks of strings and splits them on newlines. Handles both
   * `\r\n` and `\n`.
   */
  final val splitLinesChunk: ZSink[Any, Nothing, Chunk[String], Chunk[String]] =
    splitLines.mapInput[Chunk[String]](_.mkString)(Chunk.single)

  /**
   * Creates a single-value sink from a value.
   */
  final def succeed[A, B](b: B): ZSink[Any, Nothing, A, B] =
    new SinkPure[Nothing, A, B] {
      type State = Chunk[A]
      val initialPure                  = Chunk.empty
      def stepPure(state: State, a: A) = state ++ Chunk(a)
      def extractPure(state: State)    = Right((b, state))
      def cont(state: State)           = false
    }

  /**
   * Creates a sink which throttles input elements of type A according to the given bandwidth parameters
   * using the token bucket algorithm. The sink allows for burst in the processing of elements by allowing
   * the token bucket to accumulate tokens up to a `units + burst` threshold. Elements that do not meet the
   * bandwidth constraints are dropped. The weight of each element is determined by the `costFn` function.
   * Elements are mapped to `Option[A]`, and `None` denotes that a given element has been dropped.
   */
  final def throttleEnforce[A](units: Long, duration: Duration, burst: Long = 0)(
    costFn: A => Long
  ): ZManaged[Clock, Nothing, ZSink[Clock, Nothing, A, Option[A]]] =
    throttleEnforceM[Any, Nothing, A](units, duration, burst)(a => UIO.succeed(costFn(a)))

  /**
   * Creates a sink which throttles input elements of type A according to the given bandwidth parameters
   * using the token bucket algorithm. The sink allows for burst in the processing of elements by allowing
   * the token bucket to accumulate tokens up to a `units + burst` threshold. Elements that do not meet the
   * bandwidth constraints are dropped. The weight of each element is determined by the `costFn` effectful function.
   * Elements are mapped to `Option[A]`, and `None` denotes that a given element has been dropped.
   */
  final def throttleEnforceM[R, E, A](units: Long, duration: Duration, burst: Long = 0)(
    costFn: A => ZIO[R, E, Long]
  ): ZManaged[Clock, Nothing, ZSink[R with Clock, E, A, Option[A]]] = {
    import ZSink.internal._

    val maxTokens = if (units + burst < 0) Long.MaxValue else units + burst

    def bucketSink(bucket: Ref[(Long, Long)]) =
      new ZSink[R with Clock, E, A, Option[A]] {
        type State = (Ref[(Long, Long)], Option[A], Boolean)

        val initial = UIO.succeed((bucket, None, true))

        def step(state: State, a: A) =
          for {
            weight  <- costFn(a)
            current <- clock.nanoTime
            result <- state._1.modify {
                       case (tokens, timestamp) =>
                         val elapsed   = current - timestamp
                         val cycles    = elapsed.toDouble / duration.toNanos
                         val available = checkTokens(tokens + (cycles * units).toLong, maxTokens)
                         if (weight <= available)
                           ((state._1, Some(a), false), (available - weight, current))
                         else
                           ((state._1, None, false), (available, current))
                     }
          } yield result

        def extract(state: State) = UIO.succeed((state._2, Chunk.empty))

        def cont(state: State) = state._3
      }

    def checkTokens(sum: Long, max: Long): Long = if (sum < 0) max else math.min(sum, max)

    val sink = for {
      _       <- assertNonNegative(units)
      _       <- assertNonNegative(burst)
      current <- clock.nanoTime
      bucket  <- Ref.make((units, current))
    } yield bucketSink(bucket)

    ZManaged.fromEffect(sink)
  }

  /**
   * Creates a sink which delays input elements of type A according to the given bandwidth parameters
   * using the token bucket algorithm. The sink allows for burst in the processing of elements by allowing
   * the token bucket to accumulate tokens up to a `units + burst` threshold. The weight of each element is
   * determined by the `costFn` function.
   */
  final def throttleShape[A](units: Long, duration: Duration, burst: Long = 0)(
    costFn: A => Long
  ): ZManaged[Clock, Nothing, ZSink[Clock, Nothing, A, A]] =
    throttleShapeM[Any, Nothing, A](units, duration, burst)(a => UIO.succeed(costFn(a)))

  /**
   * Creates a sink which delays input elements of type A according to the given bandwidth parameters
   * using the token bucket algorithm. The sink allows for burst in the processing of elements by allowing
   * the token bucket to accumulate tokens up to a `units + burst` threshold. The weight of each element is
   * determined by the `costFn` effectful function.
   */
  final def throttleShapeM[R, E, A](units: Long, duration: Duration, burst: Long = 0)(
    costFn: A => ZIO[R, E, Long]
  ): ZManaged[Clock, Nothing, ZSink[R with Clock, E, A, A]] = {
    import ZSink.internal._

    val maxTokens = if (units + burst < 0) Long.MaxValue else units + burst

    def bucketSink(bucket: Ref[(Long, Long)]) =
      new ZSink[R with Clock, E, A, A] {
        type State = (Ref[(Long, Long)], Promise[Nothing, A], Boolean)

        val initial = Promise.make[Nothing, A].map((bucket, _, true))

        def step(state: State, a: A) =
          for {
            weight  <- costFn(a)
            current <- clock.nanoTime
            delay <- state._1.modify {
                      case (tokens, timestamp) =>
                        val elapsed    = current - timestamp
                        val cycles     = elapsed.toDouble / duration.toNanos
                        val available  = checkTokens(tokens + (cycles * units).toLong, maxTokens)
                        val remaining  = available - weight
                        val waitCycles = if (remaining >= 0) 0 else -remaining.toDouble / units
                        val delay      = Duration.Finite((waitCycles * duration.toNanos).toLong)
                        (delay, (remaining, current))
                    }
            _ <- if (delay <= Duration.Zero) UIO.unit else clock.sleep(delay)
            _ <- state._2.succeed(a)
          } yield (state._1, state._2, false)

        def extract(state: State) = state._2.await.map((_, Chunk.empty))

        def cont(state: State) = state._3
      }

    def checkTokens(sum: Long, max: Long): Long = if (sum < 0) max else math.min(sum, max)

    val sink = for {
      _       <- assertPositive(units)
      _       <- assertNonNegative(burst)
      current <- clock.nanoTime
      bucket  <- Ref.make((units, current))
    } yield bucketSink(bucket)

    ZManaged.fromEffect(sink)
  }

  /**
   * Decodes individual bytes into a String using UTF-8. Up to `bufferSize` bytes
   * will be buffered by the sink.
   *
   * This sink uses the String constructor's behavior when handling malformed byte
   * sequences.
   */
  def utf8Decode(bufferSize: Int = ZStreamChunk.DefaultChunkSize): ZSink[Any, Nothing, Byte, String] =
    foldUntil[List[Byte], Byte](Nil, bufferSize.toLong)((chunk, byte) => byte :: chunk).mapM { bytes =>
      val chunk = Chunk.fromIterable(bytes.reverse)

      for {
        init   <- utf8DecodeChunk.initial
        state  <- utf8DecodeChunk.step(init, chunk)
        string <- utf8DecodeChunk.extract(state)
      } yield string._1
    }

  /**
   * Decodes chunks of bytes into a String.
   *
   * This sink uses the String constructor's behavior when handling malformed byte
   * sequences.
   */
  val utf8DecodeChunk: ZSink[Any, Nothing, Chunk[Byte], String] =
    new SinkPure[Nothing, Chunk[Byte], String] {
      type State = (String, Chunk[Byte], Boolean)

      val initialPure = ("", Chunk.empty, true)

      def is2ByteSequenceStart(b: Byte) = (b & 0xE0) == 0xC0
      def is3ByteSequenceStart(b: Byte) = (b & 0xF0) == 0xE0
      def is4ByteSequenceStart(b: Byte) = (b & 0xF8) == 0xF0

      def computeSplit(chunk: Chunk[Byte]) = {
        // There are 3 bad patterns we need to check to detect an incomplete chunk:
        // - 2/3/4 byte sequences that start on the last byte
        // - 3/4 byte sequences that start on the second-to-last byte
        // - 4 byte sequences that start on the third-to-last byte
        //
        // Otherwise, we can convert the entire concatenated chunk to a string.
        val len = chunk.length

        if (len >= 1 &&
            (is2ByteSequenceStart(chunk(len - 1)) ||
            is3ByteSequenceStart(chunk(len - 1)) ||
            is4ByteSequenceStart(chunk(len - 1))))
          len - 1
        else if (len >= 2 &&
                 (is3ByteSequenceStart(chunk(len - 2)) ||
                 is4ByteSequenceStart(chunk(len - 2))))
          len - 2
        else if (len >= 3 && is4ByteSequenceStart(chunk(len - 3)))
          len - 3
        else len
      }

      def stepPure(state: State, a: Chunk[Byte]) =
        if (a.length == 0) (state._1, state._2, false)
        else {
          val (accumulatedString, prevLeftovers, _) = state
          val concat                                = prevLeftovers ++ a
          val (toConvert, leftovers)                = concat.splitAt(computeSplit(concat))

          if (toConvert.length == 0) (accumulatedString, leftovers, true)
          else
            (
              accumulatedString ++ new String(toConvert.toArray[Byte], "UTF-8"),
              leftovers,
              false
            )
        }

      def extractPure(state: State) = {
        val leftover = if (state._2.isEmpty) Chunk.empty else Chunk.single(state._2)
        Right((state._1, leftover))
      }

      def cont(state: State) = state._3
    }
}
