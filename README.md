#Play - Workflow

Workflow engine for Play! Framework.

## About

Write workflows/wizards with composable steps for Play! Framework apps.

A workflow is composed of steps, which are like mini-controllers. Once a step has been completed, the result is stored in the session and the flow continues to the next step.

The workflow is monadic and can be defined with for-comprehensions.

## Add to sbt project

add the following to build.sbt:
```scala
libraryDependencies += "com.github.passivsystems" % "play-workflow" % "0.0.1-SNAPSHOT"
resolvers += "jitpack" at "https://jitpack.io"
```

##Example

routes:
```
GET     /myflow/start       controllers.MyFlow.start
POST    /myflow/:stepKey    controllers.MyFlow.post(stepKey)
GET     /myflow/:stepKey    controllers.MyFlow.get(stepKey)

```

MyFlow:

```scala
import workflow._

object MyFlow extends Controller{

  val workflow: Workflow[Unit] =
    for {
      step1Result <- Workflow.step("step1", Step1())
      _           <- Workflow.step("step2", Step2(step1Result))
    } yield ()

  val wfc = WorkflowConf[Unit](
    workflow    = workflow,
    router      = routes.MyFlow)

  def get(stepId: String) = Action.async { implicit request =>
    WorkflowEngine.getWorkflow(wfc, stepId)
  }

  def post(stepId: String) = Action.async { implicit request =>
    WorkflowEngine.postWorkflow(wfc, stepId)
  }

  def start() = get("start")
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

  def apply(): Step[Step1] = {

    val form = Form(Forms.mapping(
        "name" -> Forms.nonEmptyText
      )(Step1Result.apply)(Step1Result.unapply))

    def get(ctx: WorkflowContext[Step1])(implicit request: Request[Any]): Future[Result] = {
      val filledForm = ctx.stepObject match {
        case Some(step1) => form.fill(step1)
        case None        => form
      }
      Ok(views.html.step1(ctx, filledForm))
    }

    def post(ctx: WorkflowContext[Step1])(implicit request: Request[Any]): Future[Either[Result, Step1]] = {
      val boundForm = form.bindFromRequest
      boundForm.fold(
        formWithErrors => Left(BadRequest(views.html.step1(ctx, formWithErrors)))
        step1Result    => Right(step1Result)
      )
    }

    Step[Step1](
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

}
```

and Step2:

```scala
import workflow.{Step, WorkflowContext}
object Step2 extends Controller {

  def apply(step1Result: Step1Result): Step[Unit] = {

    def get(ctx: WorkflowContext[Unit])(implicit request: Request[Any]): Future[Result] =
      Ok(views.html.step2(ctx, step1Result))

    Step[Step1](
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
}
```

Here, the first step contains a simple form to capture some data. This data is then forwarded to the next step to be displayed.

The steps do not need to know which steps came before, or which come after, as long as their data inputs are met. This means that steps can be reused in new flows.

## Running

The flow can be accessed at URL `/myflow/start` as defined in the routes file. This is an alias to the first step in the flow, which is `/myflow/step1` in this case. Page two is `/myflow/step2`. In addition to being an alias, the `start` step will clear the session of data from previous runs.


## Serialisation

Once a step has been completed (i.e. the post function returns a `Right`), the result is stored in the session, and the user may access steps further down the workflow.

The step result is stored by [pickling](http://www.lihaoyi.com/upickle-pprint/upickle/).
If the session cannot be restored, (e.g. changed domain objects), the session will be cleared, and the flow started from the beginning.

## Navigation

The steps are provided a `WorkflowContext[A]` (where `A` refers to the result of the step) which can be used for navigation.
* `ctx.actionCurrent` is the current step - forms and buttons should post to this to invoke the post function, and navigation will advance if the post function is successful.
* `ctx.actionPrevious` is the previous step - buttons can use this to go back a page. Note, actionPrevious returns an `Option[Call]`` since not all pages can go back (i.e. the first page)
* `ctx.restart` returns a `Call` pointing to the first page. In addition, it will clear the session. This may be useful to restart from error pages.
* `ctx.goto("step1")` - returns a `Call` to a step using its identifier. This should be used with caution, since it will only work if the step is defined in the current flow, and all the previous steps have been completed (results in session).

## Security

The workflow has no special handling of security. The controller is responsible for checking authentication before calling the WorkflowEngine. Any authenticated data can be fed into the flow as required, to be available to steps.

e.g.

```scala
import workflow._
import workflow.WorkflowEngine._
import workflow.Workflow.step
object MySecureFlow extends AuthController {

  def workflow(auth: Auth): Workflow[Unit] =
    for {
      step1Result     <- step("step1",     Step1(auth))
      _               <- step("step2",     Step2(auth, step1Result))
    } yield ()

  def wfc(auth: Auth) = WorkflowConf[Unit](
    workflow    = workflow(auth),
    router      = routes.MySecureFlow)

  def get(stepId: String) = Action.async { implicit request =>
    checkAuth().flatMap {
      case Left(r)     => Future(r)
      case Right(auth) => getWorkflow(wfc(auth), stepId)
    }
  }
  def post(stepId: String) = Action.async { implicit request =>
    checkAuth().flatMap {
      case Left(r)     => Future(r)
      case Right(auth) => postWorkflow(wfc(auth), stepId)
    }
  }

  def start() = get("start")
}
```

where AuthController provides `checkAuth(): Future[Either[Result,Auth]]` - which returns either a redirect to a login page, or the authenticated user.


## Monadic Workflow

Since workflows are monads, they are composable, and can be reused.

```scala
val workflow1: Workflow[Step1Result] = step("step1", Step1())

def workflow2(step1Result: Step1Result): Workflow[(Step2Result,Step3Result)] = for {
  step2Result <- step("step2", Step2(step1Result))
  step3Result <- step("step3", Step3(step1Result, step2Result))
} yield (step2Result, step3Result)

val workflow3: Workflow[Unit] = for {
  step1Result     <- workflow1
  step1And2Result <- workflow2(step1Result)
} yield ()
```

However, note that workflows are not MonadPlus and do not define `empty` or `filter`. What this means is that we cannot write an `if` filter without providing an `else`. The following uses `Workflow.pure(a)` to return a result as if a step were successful:

```scala
for {
  yes1 <- step("choose", ChooseStep())
  yes2 <- if (yes1) step("choose2"), ChooseStep())
          else      Workflow.pure(true)
}
```



And we cannot case match results like `Step1Result(name) <- step("step1", Step1Result())`, which would result in the monadic empty. We have to do the following:
```scala
for {
  step1Result <- step("step1", Step1Result())
  Step1Result(name) = step1Result
}
```
which would raise a MatchException if the case match fails.


Workflows are internally built as a transformer on top of Future, so we can also lift future actions into the Workflow:

```scala
def getBooleanFromApi(): Future[Boolean] = ???

for {
  yes1 <- Workflow.liftF(getBooleanFromApi())
  yes2 <- if (yes1) Workflow.step("choose2"), ChooseStep())
          else      Workflow.pure(true)
}
```


## TODO

* Allow custom session storing strategies (e.g. to database)
  * Provide session prefix or cookie name to engine, to segregate session for different flows
