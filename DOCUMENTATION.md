# Play - Workflow

## Documentation

### Serialisation

Once a step has been completed (i.e. the post function returns a `Right`), the result is stored for future requests, and the user may access steps further down the workflow.

If the object to be stored can be encoded/decoded with [circe](https://circe.github.io/circe/), then the default serialiser can be used. Imported with:
```scala
import workflow.implicits._
```
or explicitly:
```scala
import workflow.DefaultSerialiser._
```

To enable encoding/decoding with circe, the auto generation can be enabled with:
```scala
import io.circe.generic.auto._
```

You can provide your own serialiser, by implementing the trait `workflow.Serialiser` to provide different behaviour, and making sure the serialiser is implicitly available.

As well as indicating how to serialise an individual step object, a storage strategy can be defined. The predefined storages are:
* `SessionStorage` - which uses Play's default session which stores the data in a cookie. A key is required, and all data is stored under this key. This allows the data to participate with existing session data, including other flows if the key is unique. Each step has it's data stored as a new key, and starting a new flow will wipe all data under the key. This is the default.
* `GzippedSessionStorage` - this is the same as `SessionStorage` apart from the data is also compressed with gzip.
The storage can be set in the WorkflowConf:
```scala
  val conf = WorkflowConf[Unit](
    workflow    = workflow(auth),
    dataStorage = SubSessionStorage("myflow"),
    router      = routes.MyFlow)
```
Custom storage, e.g. to store in database, can be created by importing the trait `DataStorage`.

If the session cannot be restored, (e.g. changed domain objects), the data will be cleared, and the flow started from the beginning.

### Navigation

The steps are provided a `WorkflowContext[A]` (where `A` refers to the result of the step) which can be used for navigation.
* `ctx.actionCurrent` is the current step - forms and buttons should post to this to invoke the post function, and navigation will advance if the post function is successful.
* `ctx.actionPrevious` is the previous step - buttons can use this to go back a page. Note, actionPrevious returns an `Option[Call]`` since not all pages can go back (i.e. the first page)
* `ctx.restart` returns a `Call` pointing to the first page. In addition, it will clear the session. This may be useful to restart from error pages.
* `ctx.goto("step1")` - returns a `Call` to a step using its identifier. This should be used with caution, since it will only work if the step is defined in the current flow, and all the previous steps have been completed (results in session).

## Security

The workflow has no special handling of security. The controller is responsible for checking authentication before calling the WorkflowExecutor. Any authenticated data can be fed into the flow as required, to be available to steps.

e.g.

```scala
import workflow._
import workflow.implicits._
import workflow.WorkflowExecutor._
import workflow.Workflow.step
object MySecureFlow extends AuthController {

  def workflow(auth: Auth): Workflow[Unit] =
    for {
      step1Result     <- step("step1",     Step1(auth))
      _               <- step("step2",     Step2(auth, step1Result))
    } yield ()

  def conf(auth: Auth) = WorkflowConf[Unit](
    workflow    = workflow(auth),
    router      = routes.MySecureFlow)

  def get(stepId: String) = Action.async { implicit request =>
    checkAuth().flatMap {
      case Left(r)     => Future(r)
      case Right(auth) => getWorkflow(conf(auth), stepId)
    }
  }
  def post(stepId: String) = Action.async { implicit request =>
    checkAuth().flatMap {
      case Left(r)     => Future(r)
      case Right(auth) => postWorkflow(conf(auth), stepId)
    }
  }
}
```

where AuthController provides `checkAuth(): Future[Either[Result,Auth]]` - which returns either a redirect to a login page, or the authenticated user.


### Monadic Workflow

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
  yes2 <- if (yes1) step("choose2", ChooseStep())
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

### Executing futures in workflows

* `Workflow.liftF`

We can lift any future into the workflow with `Workflow.liftF`:

```scala
def getBooleanFromApi(): Future[Boolean] = ???

for {
  yes1 <- Workflow.liftF(getBooleanFromApi())
  yes2 <- if (yes1) Workflow.step("choose2", ChooseStep())
          else      Workflow.pure(true)
} yield yes2
```

The future is will be invoked on each submit.

* `Step.liftF`

Another option is to create a step out of a Future using `Step.liftF`. The step can be included in the workflow like any other step:

```scala
for {
  yes1 <- Workflow.step("choose1", Step.liftF(getBooleanFromApi()))
  yes2 <- if (yes1) Workflow.step("choose2", ChooseStep())
          else      Workflow.pure(true)
} yield yes2
```
Since step takes a label, the result of the future will be stored in the session like any other step result.

This will cause problems when going back, since a back from step "choose2" will repeat the future "choose1".

* `Workflow.cache`

A better option is to include the Step with `Workflow.cache` instead of `Workflow.step`. This will cache the result of the step, but will be skipped when we call `ctx.actionPrevious`:

```scala
for {
  yes1 <- Workflow.cache("choose1", Step.liftF(getBooleanFromApi()))
  yes2 <- if (yes1) Workflow.step("choose2", ChooseStep())
          else      Workflow.pure(true)
} yield yes2
```

### Transforming step results

The result of a Step may be mapped before the result is stored.
e.g.

```scala
def getBooleanFromApi(): Future[Boolean] = ???

def workflow: Workflow[String] = for {
  yes1            <- Workflow.step("choose1", ChooseStep()) // : Boolean
  yes2Transformed <- Workflow.step("choose2", ChooseStep().map(yes2 -> if (yes2) "A" else "B")) // : String
} yield yes2Transformed
```
Note, however, if we transform the output of a step before storing in the session, then when we return to step "choose2" (i.e. with `ctx.goto` or `ctx.actionPrevious`), since the value stored in the session is a String, the `ctx.previousObject` will be None since it requires a Boolean. (This would break the Functor laws)

In addition to `map`, there is `semiflatMap` which takes `f: A => Future[B]`.

Given the problem with returning to the step, it is probably better to transform the result as a separate workflow step, using `Step.pure`. E.g.:

```scala
def getBooleanFromApi(): Future[Boolean] = ???

def workflow: Workflow[String] = for {
  yes1 <- Workflow.step("choose1", ChooseStep()) // : Boolean
  yes2 <- Workflow.step("choose2", ChooseStep()) // : Boolean
  yes2Transformed <- Workflow.cache("choose2-transform", Step.pure(if (yes2) "A" else "B")) // : String  
} yield yes2Transformed
```


### Web Sockets

Steps can use websockets.
First register the endpoint in routes:
```scala
GET     /myflow/:stepKey/ws        MyFlow.ws(stepKey)
```

and add the endpoint to the controller:
```scala
object MyFlow extends Controller {
  // ...
  def ws(stepId: String) = WebSocket { implicit request =>
    WorkflowExecutor.wsWorkflow(conf, stepId)
  }
}
```
The weboscket only needs to be added to steps where it is relevant. However a call to the websocket url for a step which has not been implemented will fail:

```scala
import play.api.Play.current
object MyStep extends Controller {
  def apply(): Step[StepOut] {

    // ..

    def ws(ctx: WorkflowContext[StepOut])(implicit request: RequestHeader) =
      WebSocket.accept[String, String] { implicit request =>
        ActorFlow.actorRef(out => Props(new MyStepActor(out)))
      }

    Step[StepOut](
        get  = ???,
        post = ???,
        ws = ctx => request => Future(Some(ws(ctx)(request)))
      )
  }
}
```

The websocket will have the context, and any step inputs available in the same way as get and post.

The websocket currently only reads/writes Strings. If you require anything other than String, you will have to serialise/deserialise the payloads yourself.

Post will still have to be called to advance to the next step, so you will have to ensure that any data is included in the post which you want to be included in the step output.
