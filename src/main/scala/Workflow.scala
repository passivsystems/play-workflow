package workflow

import scala.concurrent.{ExecutionContext, Future}
//import cats.Functor
import cats.free.FreeT


/** exposes Monadic functions directly on Workflow (fixed for FreeT[Future]) */
object Workflow extends cats.Monad[Workflow] {

  /** Wraps a Step as a Workflow so can be composed in a Workflow
   *  @param label the name of the step. Is used in URLs to identify the current step,
   *         and as the key to the result when stored - needs to be unique in the workflow
   *  @param step the step to be included in the workflow
   *  @param reader defines how to read the step result out of the session
   *  @param writer defines how to write the step result to the session
   */
  def step[A](label: String, step: Step[A])(implicit serialiser: Serialiser[A], ec: ExecutionContext): Workflow[A] =
    FreeT.liftF[WorkflowSyntax, Future, A](WorkflowSyntax.WSStep[A,A](label, step, serialiser, identity))


  // workflow cannot be a monadPlus unless A is a Monoid...
//object Workflow extends cats.MonadFilter[Workflow] {
  //def empty[A]: Workflow[A] = pure(A.empty) // Free.liftF(WorkflowSyntax.Nothing())

  override def flatMap[A, B](fa: Workflow[A])(f: A => Workflow[B]): Workflow[B] =
    fa.flatMap(f)

  override def tailRecM[A, B](a: A)(f: A => Workflow[Either[A,B]]): Workflow[B] = {
    implicit val ec = play.api.libs.concurrent.Execution.defaultContext
    FreeT.tailRecM(a)(f)
  }

  override def pure[A](x: A): Workflow[A] = {
    implicit val ec = play.api.libs.concurrent.Execution.defaultContext
    FreeT.pure[WorkflowSyntax, Future, A](x)
  }

  def liftF[A](f: Future[A])(implicit ec: ExecutionContext): Workflow[A] =
    FreeT.liftTU(f)

  // TODO make tail recursive
  def sequence[A](l: List[Workflow[A]])(implicit ec: ExecutionContext): Workflow[List[A]] =
    l match {
      case wfa :: rest => for {
                             a   <- wfa
                             acc <- sequence(rest)
                           } yield a :: acc
      case Nil         => pure[List[A]](Nil)
    }
}

object WorkflowOps {
  implicit class ListWithSequence[A](l: List[Workflow[A]])(implicit ec: ExecutionContext) {
    def sequence: Workflow[List[A]] = Workflow.sequence(l)
  }
}
