# Play - Workflow

Workflow engine for Play! Framework (2.5.x).

## About

Write workflows/wizards with composable steps for Play! Framework apps.

A workflow is composed of steps, which are like mini-controllers. Once a step has been completed, the result is stored in the session and the flow continues to the next step.

The workflow is monadic and can be defined with for-comprehensions.

### Add to sbt project

add the following to build.sbt:
```scala
libraryDependencies += "com.github.passivsystems" % "play-workflow" % "0.2.0"
resolvers += "jitpack" at "https://jitpack.io"
```

### Example

routes:
```
POST    /myflow/:stepKey    controllers.MyFlow.post(stepKey)
GET     /myflow/:stepKey    controllers.MyFlow.get(stepKey)

```

MyFlow:

```scala
import workflow._
import workflow.implicits._

object MyFlow extends Controller {

  val workflow: Workflow[Unit] =
    for {
      step1Result <- Workflow.step("step1", Step1())
      _           <- Workflow.step("step2", Step2(step1Result))
    } yield ()

  val conf = WorkflowConf[Unit](
    workflow    = workflow,
    router      = routes.MyFlow)

  def get(stepId: String) = Action.async { implicit request =>
    WorkflowExecutor.getWorkflow(conf, stepId)
  }

  def post(stepId: String) = Action.async { implicit request =>
    WorkflowExecutor.postWorkflow(conf, stepId)
  }
}
```

We register the flow in the routes file, and then map the endpoints to the workflow engine, providing our workflow.

Here, the workflow has been defined with two steps. The output of the first step has been provided to the second step.

The steps can be defined as follows:

Step1:

```scala
import workflow.{Step, WorkflowContext}

case class Step1Result(name: String)

object Step1 extends Controller {

  def apply(): Step[Step1Result] = {

    val form = Form(Forms.mapping(
        "name" -> Forms.nonEmptyText
      )(Step1Result.apply)(Step1Result.unapply))

    def get(ctx: WorkflowContext[Step1Result])(implicit request: Request[Any]): Future[Result] = Future {
      val filledForm = ctx.stepObject match {
        case Some(step1) => form.fill(step1)
        case None        => form
      }
      Ok(views.html.step1(ctx, filledForm))
    }

    def post(ctx: WorkflowContext[Step1Result])(implicit request: Request[Any]): Future[Either[Result, Step1Result]] = Future {
      val boundForm = form.bindFromRequest
      boundForm.fold(
        formWithErrors => Left(BadRequest(views.html.step1(ctx, formWithErrors))),
        step1Result    => Right(step1Result)
      )
    }

    Step[Step1Result](
      get  = ctx => request => get(ctx)(request).map(Some(_)),
      post = ctx => request => post(ctx)(request)
    )
  }
}
```

step1.scala.html:

```scala
@(ctx: workflow.WorkflowContext[Step1Result], stepForm: Form[Step1Result])

@stepForm.globalError.map { error => @Html(Messages(error.message)) }
<form role="form" method="post" action="@ctx.actionCurrent">
  @inputText(stepForm("name"))
  <button type="submit">Next</button>
</form>
```

and Step2:

```scala
import workflow.{Step, WorkflowContext}
object Step2 extends Controller {

  def apply(step1Result: Step1Result): Step[Unit] = {

    def get(ctx: WorkflowContext[Unit])(implicit request: Request[Any]): Future[Result] =
      Future(Ok(views.html.step2(ctx, step1Result)))

    Step[Unit](
      get  = ctx => request => get(ctx)(request).map(Some(_)),
      post = ctx => request => Future(Right(()))
    )
  }
}

```

step2.scala.html:

```scala
@(ctx: workflow.WorkflowContext[Unit], step1Result: Step1Result)

<p>Hello @step1Result.name</p>

@ctx.actionPrevious.map { previous =>
  <a href="@previous">Back</a>
}
```

Here, the first step contains a simple form to capture some data. This data is then forwarded to the next step to be displayed.

The steps do not need to know which steps came before, or which come after, as long as their data inputs are met. This means that steps can be reused in new flows.

### Running

The flow can be accessed at URL `/myflow/start` as defined in the routes file. This is an alias to the first step in the flow, which is `/myflow/step1` in this case. Page two is `/myflow/step2`. In addition to being an alias, the `start` step will clear the session of data from previous runs.

### Documentation

More details are available in [DOCUMENTATION.md](DOCUMENTATION.md).
