package workflow

import cats.{Applicative, Functor, Monad}
import cats.data.EitherT
import play.api.mvc.{Request, RequestHeader, Result, WebSocket}


/** A single step in a Workflow.
 *  @constructor create a new Step with get, post and optionally ws.
 *  @param get defines the steps initial loading. If returns None, then the initial request will go straight to {{{post}}}.
 *  @param post defines the steps processing. This may involve form validation, which may return a Result, if failed, or
 *              a value to be stored in the session, and made accessible to the following steps.
 *  @param ws optionally, steps may support WebSockets. The websockets will have access
 *            to the context, and any step data just as get and post. However it is expected
 *            that a post will be made to advance the workflow.
 */
final case class StepT[F[_],A](
  get:  WorkflowContext[A] => Request[Any]  => F[Option[Result]],
  post: WorkflowContext[A] => Request[Any]  => F[Either[Result, A]],
  ws:   WorkflowContext[A] => RequestHeader => F[Option[WebSocket[String, String]]]  // TODO support any input, output (not just String) if a reader and writer are provided.
) {
  // help! need to transform b back to a! Do we need to store the original object in the session?
  private def t[B,C](ctx: WorkflowContext[B]): WorkflowContext[C] =
    ctx.copy(stepObject = None)

  private def updatePost[B](update: EitherT[F, Result, A] => EitherT[F, Result, B]): StepT[F,B] =
    StepT[F,B](
      get  = (ctx: WorkflowContext[B]) => (request: Request[Any])  => this.get(t(ctx))(request),
      post = (ctx: WorkflowContext[B]) => (request: Request[Any])  => update(EitherT(this.post(t(ctx))(request))).value,
      ws   = (ctx: WorkflowContext[B]) => (request: RequestHeader) => this.ws(t(ctx))(request)
    )

  def map[B](f: A => B)(implicit F: Functor[F]): StepT[F,B] =
    updatePost(_.map(f))

  def semiflatMap[B](f: A => F[B])(implicit F: Monad[F]): StepT[F,B] =
    updatePost(_.semiflatMap(f))
}

object StepT {
  def pure[F[_], A](x: A)(implicit A: Applicative[F]): StepT[F, A] =
    liftF(A.pure(x))

  def liftF[F[_], A](f: => F[A])(implicit A: Applicative[F]): StepT[F, A] =
      StepT(
        get  = (ctx: WorkflowContext[A]) => (req: Request[Any])  => A.pure[Option[Result]](None),
        post = (ctx: WorkflowContext[A]) => (req: Request[Any])  => A.map(f)(Right(_): Either[Result,A]),
        ws   = (ctx: WorkflowContext[A]) => (req: RequestHeader) => A.pure[Option[WebSocket[String,String]]](None))
}

trait StepTInstances {
  implicit def catsFunctorForStepT[F[_]](implicit F0: Functor[F]) =
    new StepTFunctor[F] { implicit val F = F0 }
  private [workflow] sealed trait StepTFunctor[F[_]] extends Functor[StepT[F, ?]] {
    implicit def F: Functor[F]
    def map[A, B](fa: StepT[F,A])(f: A => B): StepT[F,B] = fa.map(f)
  }
}
object StepTInstances extends StepTInstances
