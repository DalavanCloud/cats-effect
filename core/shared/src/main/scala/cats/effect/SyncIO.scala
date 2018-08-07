/*
 * Copyright (c) 2017-2018 The Typelevel Cats-effect Project Developers
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

package cats
package effect

import scala.annotation.unchecked.uncheckedVariance

/**
 * A pure abstraction representing the intention to perform a
 * side effect, where the result of that side effect is obtained
 * synchronously.
 *
 * `SyncIO` is similar to [[IO]], but does not support asynchronous
 * computations. Consequently, a `SyncIO` can be run synchronously
 * to obtain a result via `unsafeRunSync`. This is unlike
 * `IO#unsafeRunSync`, which cannot be safely called in general -- 
 * doing so on the JVM blocks the calling thread while the
 * async part of the computation is run and doing so on Scala.JS
 * throws an exception upon encountering an async boundary.
 */
final class SyncIO[+A] private (val toIO: IO[A]) {

  /**
   * Produces the result by running the encapsulated effects as impure
   * side effects.
   *
   * Any exceptions raised within the effect will be re-thrown during
   * evaluation.
   *
   * As the name says, this is an UNSAFE function as it is impure and
   * performs side effects and throws exceptions. You should ideally
   * only call this function *once*, at the very end of your program.
   */
  def unsafeRunSync(): A = toIO.unsafeRunSync

  /**
   * Functor map on `SyncIO`. Given a mapping function, it transforms the
   * value produced by the source, while keeping the `SyncIO` context.
   *
   * Any exceptions thrown within the function will be caught and
   * sequenced in to the result `SyncIO[B]`.
   */
  def map[B](f: A => B): SyncIO[B] = new SyncIO(toIO.map(f))

  /**
   * Monadic bind on `SyncIO`, used for sequentially composing two `SyncIO`
   * actions, where the value produced by the first `SyncIO` is passed as
   * input to a function producing the second `SyncIO` action.
   *
   * Due to this operation's signature, `flatMap` forces a data
   * dependency between two `SyncIO` actions, thus ensuring sequencing
   * (e.g. one action to be executed before another one).
   *
   * Any exceptions thrown within the function will be caught and
   * sequenced in to the result `SyncIO[B].
   */
  def flatMap[B](f: A => SyncIO[B]): SyncIO[B] = new SyncIO(toIO.flatMap(a => f(a).toIO))

  /**
   * Materializes any sequenced exceptions into value space, where
   * they may be handled.
   *
   * This is analogous to the `catch` clause in `try`/`catch`, being
   * the inverse of `SyncIO.raiseError`. Thus:
   *
   * {{{
   * SyncIO.raiseError(ex).attempt.unsafeRunSync === Left(ex)
   * }}}
   *
   * @see [[SyncIO.raiseError]]
   */
  def attempt: SyncIO[Either[Throwable, A]] = new SyncIO(toIO.attempt)

  /**
   * Converts the source `IO` into any `F` type that implements
   * the [[LiftIO]] type class.
   */
  def to[F[_]](implicit F: LiftIO[F]): F[A @uncheckedVariance] =
    F.liftIO(toIO)

  /**
   * Returns a `SyncIO` action that treats the source task as the
   * acquisition of a resource, which is then exploited by the `use`
   * function and then `released`.
   *
   * The `bracket` operation is the equivalent of the
   * `try {} catch {} finally {}` statements from mainstream languages.
   *
   * The `bracket` operation installs the necessary exception handler
   * to release the resource in the event of an exception being raised
   * during the computation.
   *
   * If an exception is raised, then `bracket` will re-raise the
   * exception ''after'' performing the `release`.
   *
   * '''NOTE on error handling''': one big difference versus
   * `try/finally` statements is that, in case both the `release`
   * function and the `use` function throws, the error raised by `use`
   * gets signaled.
   *
   * For example:
   *
   * {{{
   *   SyncIO("resource").bracket { _ =>
   *     // use
   *     SyncIO.raiseError(new RuntimeException("Foo"))
   *   } { _ =>
   *     // release
   *     SyncIO.raiseError(new RuntimeException("Bar"))
   *   }
   * }}}
   *
   * In this case the error signaled downstream is `"Foo"`, while the
   * `"Bar"` error gets reported. This is consistent with the behavior
   * of Haskell's `bracket` operation and NOT with `try {} finally {}`
   * from Scala, Java or JavaScript.
   *
   * @see [[bracketCase]]
   *
   * @param use is a function that evaluates the resource yielded by
   *        the source, yielding a result that will get generated by
   *        the task returned by this `bracket` function
   *
   * @param release is a function that gets called after `use`
   *        terminates, either normally or in error, or if it gets
   *        canceled, receiving as input the resource that needs to
   *        be released
   */
  def bracket[B](use: A => SyncIO[B])(release: A => SyncIO[Unit]): SyncIO[B] =
    bracketCase(use)((a, _) => release(a))

  /**
   * Returns a new `SyncIO` task that treats the source task as the
   * acquisition of a resource, which is then exploited by the `use`
   * function and then `released`, with the possibility of
   * distinguishing between normal termination and failure, such
   * that an appropriate release of resources can be executed.
   *
   * The `bracketCase` operation is the equivalent of
   * `try {} catch {} finally {}` statements from mainstream languages
   * when used for the acquisition and release of resources.
   *
   * The `bracketCase` operation installs the necessary exception handler
   * to release the resource in the event of an exception being raised
   * during the computation.
   *
   * In comparison with the simpler [[bracket]] version, this one
   * allows the caller to differentiate between normal termination and
   * termination in error. Note `SyncIO` does not support cancelation
   * so that exit case should be ignored.
   *
   * @see [[bracket]]
   *
   * @param use is a function that evaluates the resource yielded by
   *        the source, yielding a result that will get generated by
   *        this function on evaluation
   *
   * @param release is a function that gets called after `use`
   *        terminates, either normally or in error, receiving
   *        as input the resource that needs that needs release,
   *        along with the result of `use` (error or successful result)
   */
  def bracketCase[B](use: A => SyncIO[B])(release: (A, ExitCase[Throwable]) => SyncIO[Unit]): SyncIO[B] =
    new SyncIO(toIO.bracketCase(a => use(a).toIO)((a, ec) => release(a, ec).toIO))

  /**
   * Executes the given `finalizer` when the source is finished,
   * either in success or in error.
   *
   * This variant of [[guaranteeCase]] evaluates the given `finalizer`
   * regardless of how the source gets terminated:
   *
   *  - normal completion
   *  - completion in error
   *
   * This equivalence always holds:
   *
   * {{{
   *   io.guarantee(f) <-> IO.unit.bracket(_ => io)(_ => f)
   * }}}
   *
   * As best practice, it's not a good idea to release resources
   * via `guaranteeCase` in polymorphic code. Prefer [[bracket]]
   * for the acquisition and release of resources.
   *
   * @see [[guaranteeCase]] for the version that can discriminate
   *      between termination conditions
   *
   * @see [[bracket]] for the more general operation
   */
  def guarantee(finalizer: SyncIO[Unit]): SyncIO[A] =
    guaranteeCase(_ => finalizer)

  /**
   * Executes the given `finalizer` when the source is finished,
   * either in success or in error, allowing
   * for differentiating between exit conditions.
   *
   * This variant of [[guarantee]] injects an [[ExitCase]] in
   * the provided function, allowing one to make a difference
   * between:
   *
   *  - normal completion
   *  - completion in error
   *
   * This equivalence always holds:
   *
   * {{{
   *   io.guaranteeCase(f) <-> IO.unit.bracketCase(_ => io)((_, e) => f(e))
   * }}}
   *
   * As best practice, it's not a good idea to release resources
   * via `guaranteeCase` in polymorphic code. Prefer [[bracketCase]]
   * for the acquisition and release of resources.
   *
   * @see [[guarantee]] for the simpler version
   *
   * @see [[bracketCase]] for the more general operation
   */
  def guaranteeCase(finalizer: ExitCase[Throwable] => SyncIO[Unit]): SyncIO[A] =
    new SyncIO(toIO.guaranteeCase(ec => finalizer(ec).toIO))

  /**
   * Handle any error, potentially recovering from it, by mapping it to another
   * `SyncIO` value.
   *
   * Implements `ApplicativeError.handleErrorWith`.
   */
  def handleErrorWith[AA >: A](f: Throwable => SyncIO[AA]): SyncIO[AA] =
    new SyncIO(toIO.handleErrorWith(t => f(t).toIO))

  /**
   * Returns a new value that transforms the result of the source,
   * given the `recover` or `map` functions, which get executed depending
   * on whether the result is successful or if it ends in error.
   *
   * This is an optimization on usage of [[attempt]] and [[map]],
   * this equivalence being true:
   *
   * {{{
   *   io.redeem(recover, map) <-> io.attempt.map(_.fold(recover, map))
   * }}}
   *
   * Usage of `redeem` subsumes `handleError` because:
   *
   * {{{
   *   io.redeem(fe, id) <-> io.handleError(fe)
   * }}}
   *
   * @param recover is a function used for error recover in case the
   *        source ends in error
   * @param map is a function used for mapping the result of the source
   *        in case it ends in success
   */
  def redeem[B](recover: Throwable => B, map: A => B): SyncIO[B] =
    new SyncIO(toIO.redeem(recover, map))

  /**
   * Returns a new value that transforms the result of the source,
   * given the `recover` or `bind` functions, which get executed depending
   * on whether the result is successful or if it ends in error.
   *
   * This is an optimization on usage of [[attempt]] and [[flatMap]],
   * this equivalence being available:
   *
   * {{{
   *   io.redeemWith(recover, bind) <-> io.attempt.flatMap(_.fold(recover, bind))
   * }}}
   *
   * Usage of `redeemWith` subsumes `handleErrorWith` because:
   *
   * {{{
   *   io.redeemWith(fe, F.pure) <-> io.handleErrorWith(fe)
   * }}}
   *
   * Usage of `redeemWith` also subsumes [[flatMap]] because:
   *
   * {{{
   *   io.redeemWith(F.raiseError, fs) <-> io.flatMap(fs)
   * }}}
   *
   * @param recover is the function that gets called to recover the source
   *        in case of error
   * @param bind is the function that gets to transform the source
   *        in case of success
   */
  def redeemWith[B](recover: Throwable => SyncIO[B], bind: A => SyncIO[B]): SyncIO[B] =
    new SyncIO(toIO.redeemWith(t => recover(t).toIO, a => bind(a).toIO))

  override def toString: String = toIO match {
    case IO.Pure(a) => s"SyncIO($a)"
    case IO.RaiseError(e) => s"SyncIO(throw $e)"
    case _ => "SyncIO$" + System.identityHashCode(this)
  }
}

object SyncIO {

  /**
   * Suspends a synchronous side effect in `SyncIO`.
   *
   * Any exceptions thrown by the effect will be caught and sequenced
   * into the `SyncIO`.
   */
  def apply[A](thunk: => A): SyncIO[A] = new SyncIO(IO(thunk))

  /**
   * Suspends a synchronous side effect which produces a `SyncIO` in `SyncIO`.
   *
   * This is useful for trampolining (i.e. when the side effect is
   * conceptually the allocation of a stack frame).  Any exceptions
   * thrown by the side effect will be caught and sequenced into the
   * `SyncIO`.
   */
  def suspend[A](thunk: => SyncIO[A]): SyncIO[A] = new SyncIO(IO.suspend(thunk.toIO))

  /**
   * Suspends a pure value in `SyncIO`.
   *
   * This should ''only'' be used if the value in question has
   * "already" been computed!  In other words, something like
   * `SyncIO.pure(readLine)` is most definitely not the right thing to do!
   * However, `SyncIO.pure(42)` is correct and will be more efficient
   * (when evaluated) than `SyncIO(42)`, due to avoiding the allocation of
   * extra thunks.
   */
  def pure[A](a: A): SyncIO[A] = new SyncIO(IO.pure(a))

  /** Alias for `SyncIO.pure(())`. */
  val unit: SyncIO[Unit] = pure(())

  /**
   * Lifts an `Eval` into `SyncIO`.
   *
   * This function will preserve the evaluation semantics of any
   * actions that are lifted into the pure `SyncIO`.  Eager `Eval`
   * instances will be converted into thunk-less `SyncIO` (i.e. eager
   * `SyncIO`), while lazy eval and memoized will be executed as such.
   */
  def eval[A](fa: Eval[A]): SyncIO[A] = fa match {
    case Now(a) => pure(a)
    case notNow => apply(notNow.value)
  }

  /**
   * Constructs a `SyncIO` which sequences the specified exception.
   *
   * If this `SyncIO` is run using `unsafeRunSync` the exception will
   * be thrown.  This exception can be "caught" (or rather, materialized
   * into value-space) using the `attempt` method.
   *
   * @see [[SyncIO#attempt]]
   */
  def raiseError[A](e: Throwable): SyncIO[A] = new SyncIO(IO.raiseError(e))

  /**
   * Lifts an `Either[Throwable, A]` into the `SyncIO[A]` context, raising
   * the throwable if it exists.
   */
  def fromEither[A](e: Either[Throwable, A]): SyncIO[A] = new SyncIO(IO.fromEither(e))
}