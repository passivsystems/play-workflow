package workflow

import scala.concurrent.{ExecutionContext, Future}
import cats.{Applicative, Monad}
import cats.free.FreeT
import play.api.mvc.{Call, Request, RequestHeader, Result, WebSocket}


case class WorkflowConf[A](
  workflow:    Workflow[A],
  dataStorage: DataStorage = SessionStorage,
  router:      {def post(stepKey: String): Call; def get(stepKey: String): Call},
  start:       String = "start"
)

case class WorkflowContext[A] private [workflow] (
  actionCurrent:  Call,
  actionPrevious: Option[Call],
  stepObject:     Option[A],
  restart:        Call,
  goto:           String => Call
)

private [workflow] sealed trait WorkflowSyntax[+Next]
private [workflow] object WorkflowSyntax {
  case class WSStep[A, Next](label: String, step: Step[A], serialiser: Serialiser[A], cache: Boolean, next: A => Next) extends WorkflowSyntax[Next]
}

trait WorkflowInstances {
  implicit def catsMonadForWorkflow(implicit M0: Monad[Future], A0: Applicative[Future], ec: ExecutionContext) =
    new WorkflowMonad { implicit val M = M0; implicit val A = A0 }

  private [workflow] sealed trait WorkflowMonad extends Monad[Workflow] {
    implicit def M: Monad[Future]
    implicit def A: Applicative[Future]

    override def flatMap[A, B](fa: Workflow[A])(f: A => Workflow[B]): Workflow[B] =
      fa.flatMap(f)

    override def tailRecM[A, B](a: A)(f: A => Workflow[Either[A,B]]): Workflow[B] =
      FreeT.tailRecM(a)(f)

    override def pure[A](x: A): Workflow[A] =
      FreeT.pure[WorkflowSyntax, Future, A](x)
  }
}

/** exposes Monadic functions directly on Workflow (fixed for FreeT[Future])
 *  rather than depending on cats (Applicative[Workflow].pure)
 */
object Workflow {

  import cats.implicits._

  /** Wraps a Step as a Workflow so can be composed in a Workflow
   *  @param label the name of the step. Is used in URLs to identify the current step,
   *         and as the key to the result when stored - needs to be unique in the workflow
   *  @param step the step to be included in the workflow
   *  @param reader defines how to read the step result out of the session
   *  @param writer defines how to write the step result to the session
   */
  def step[A](label: String, step: Step[A])(implicit serialiser: Serialiser[A], ec: ExecutionContext): Workflow[A] =
    FreeT.liftF[WorkflowSyntax, Future, A](WorkflowSyntax.WSStep[A,A](label, step, serialiser, false, identity))

  def cache[A](label: String, step: Step[A])(implicit serialiser: Serialiser[A], ec: ExecutionContext): Workflow[A] =
    FreeT.liftF[WorkflowSyntax, Future, A](WorkflowSyntax.WSStep[A,A](label, step, serialiser, true, identity))

  def liftF[A](f: Future[A])(implicit ec: ExecutionContext): Workflow[A] =
    FreeT.liftTU(f)

  def pure[A](x: A)(implicit ec: ExecutionContext): Workflow[A] =
    FreeT.pure[WorkflowSyntax, Future, A](x)
}

object Step {
  implicit val ec = play.api.libs.concurrent.Execution.defaultContext
  def apply[A](
    get:  WorkflowContext[A] => Request[Any]  => Future[Option[Result]] =
      (ctx: WorkflowContext[A]) => (req: RequestHeader) => Future(None),
    post: WorkflowContext[A] => Request[Any]  => Future[Either[Result, A]],
    ws:   WorkflowContext[A] => RequestHeader => Future[Option[WebSocket]] =
      (ctx: WorkflowContext[A]) => (req: RequestHeader) => Future(None)) =
      StepT[Future, A](get, post, ws)

 def pure[A](x: A)(implicit A: Applicative[Future]): Step[A] =
   StepT.pure[Future, A](x)

  def liftF[A](f: Future[A])(implicit F: Applicative[Future]): Step[A] =
    StepT.liftF(f)

  def liftFE[A](f: Future[Either[Result,A]]): Step[A] =
    Step(post = (ctx: WorkflowContext[A]) => (req: Request[Any]) => f)
}

trait AllInstances
  extends StepTInstances
  with    UpickleSerialiser
  with    WorkflowInstances
  with    cats.instances.FutureInstances
object implicits extends AllInstances
