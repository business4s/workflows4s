"use strict";(self.webpackChunkwebsite=self.webpackChunkwebsite||[]).push([[742],{4838:(e,t,n)=>{n.r(t),n.d(t,{assets:()=>l,contentTitle:()=>o,default:()=>h,frontMatter:()=>r,metadata:()=>i,toc:()=>d});var s=n(4848),a=n(8453);const r={sidebar_position:1,sidebar_label:"Intro"},o="Intro",i={id:"index",title:"Intro",description:"As of now, Workflows4s don't have a realease. The guide below aims at showcasing the basic ideas behind the library but",source:"@site/docs/index.md",sourceDirName:".",slug:"/",permalink:"/workflows4s/docs/",draft:!1,unlisted:!1,tags:[],version:"current",sidebarPosition:1,frontMatter:{sidebar_position:1,sidebar_label:"Intro"},sidebar:"tutorialSidebar",next:{title:"Operations",permalink:"/workflows4s/docs/category/operations"}},l={},d=[{value:"Modeling the workflow",id:"modeling-the-workflow",level:2},{value:"Implementing the workflow",id:"implementing-the-workflow",level:2},{value:"Running the workflow",id:"running-the-workflow",level:2},{value:"Recovering the workflow",id:"recovering-the-workflow",level:2}];function c(e){const t={a:"a",admonition:"admonition",code:"code",h1:"h1",h2:"h2",img:"img",li:"li",ol:"ol",p:"p",pre:"pre",...(0,a.R)(),...e.components};return(0,s.jsxs)(s.Fragment,{children:[(0,s.jsx)(t.h1,{id:"intro",children:"Intro"}),"\n",(0,s.jsx)(t.admonition,{type:"info",children:(0,s.jsxs)(t.p,{children:["As of now, Workflows4s don't have a realease. The guide below aims at showcasing the basic ideas behind the library but\nto follow it you need to either release the code locally or write it inside ",(0,s.jsx)(t.code,{children:"workflows4s-example"})," project."]})}),"\n",(0,s.jsx)(t.p,{children:"Let's model a simplified pull request process that looks like this:"}),"\n",(0,s.jsxs)(t.ol,{children:["\n",(0,s.jsx)(t.li,{children:"Run CI/CD pipeline"}),"\n",(0,s.jsx)(t.li,{children:"Close the PR if critical issue detected in the pipeline"}),"\n",(0,s.jsx)(t.li,{children:"Await approval"}),"\n",(0,s.jsx)(t.li,{children:"Merge if approved, close otherwise"}),"\n"]}),"\n",(0,s.jsx)(t.h2,{id:"modeling-the-workflow",children:"Modeling the workflow"}),"\n",(0,s.jsx)(t.p,{children:"We will start by defining our workflow context. It controls types internal to the workflow: its state and events it uses\nfor persistance. Those won't bother us for now because they are not important in the early phase of designing the\nworkflow."}),"\n",(0,s.jsx)(t.pre,{children:(0,s.jsx)(t.code,{className:"language-scala",metastring:"file=./main/scala/workflow4s/example/docs/pullrequest/PullRequestWorkflowDraft.scala start=start_context end=end_context",children:"object Context extends WorkflowContext {\n  override type Event = Unit\n  override type State = Unit\n}\nimport Context.*\n"})}),"\n",(0,s.jsx)(t.p,{children:"Now we can define the shape of our workflow."}),"\n",(0,s.jsx)(t.pre,{children:(0,s.jsx)(t.code,{className:"language-scala",metastring:"file=./main/scala/workflow4s/example/docs/pullrequest/PullRequestWorkflowDraft.scala start=start_steps end=end_steps",children:'val createPR: WIO.Draft    = WIO.draft.signal()\nval runPipeline: WIO.Draft = WIO.draft.step(error = "Critical Issue")\nval awaitReview: WIO.Draft = WIO.draft.signal(error = "Rejected")\n\nval mergePR: WIO.Draft = WIO.draft.step()\nval closePR: WIO.Draft = WIO.draft.step()\n\nval workflow: WIO.Draft = (\n  createPR >>>\n    runPipeline >>>\n    awaitReview >>>\n    mergePR\n).handleErrorWith(closePR)\n'})}),"\n",(0,s.jsx)(t.p,{children:"This is enough to generate the graphical representation!"}),"\n",(0,s.jsx)(t.pre,{children:(0,s.jsx)(t.code,{className:"language-scala",metastring:"file=./main/scala/workflow4s/example/docs/pullrequest/PullRequestWorkflowDraft.scala start=start_render end=end_render",children:'val bpmnModel = BPMNConverter.convert(workflow.getModel, "process")\nBpmn.writeModelToFile(new File(s"pr-draft.bpmn").getAbsoluteFile, bpmnModel)\n'})}),"\n",(0,s.jsx)(t.p,{children:"Tada!"}),"\n",(0,s.jsx)(t.p,{children:(0,s.jsx)(t.img,{alt:"run-io.svg",src:n(9207).A+"",width:"1176",height:"318"})}),"\n",(0,s.jsx)(t.h2,{id:"implementing-the-workflow",children:"Implementing the workflow"}),"\n",(0,s.jsx)(t.p,{children:"Let's now implement our workflow. We have to start with defining the few underlying ADTs: state, events, errors and\nsignals. Normally, you will define those as you go through the process od defining the steps, but for the sake of this\ntutorial, we are defining them upfront."}),"\n",(0,s.jsx)(t.pre,{children:(0,s.jsx)(t.code,{className:"language-scala",metastring:"file=./main/scala/workflow4s/example/docs/pullrequest/PullRequestWorkflow.scala start=start_state end=end_state",children:"sealed trait PRState\nobject PRState {\n  case object Empty                                                               extends PRState\n  case class Initiated(commit: String)                                            extends PRState\n  case class Checked(commit: String, pipelineResults: String)                     extends PRState\n  case class Reviewed(commit: String, pipelineResults: String, approved: Boolean) extends PRState\n  type Merged = Reviewed\n  case class Closed(state: PRState, reason: PRError) extends PRState\n}\n"})}),"\n",(0,s.jsx)(t.p,{children:"Workflows4s was designed to be as type-safe as possible. It means we can express the state as an ADT and the compiler\nwill allow us to compose steps transitioning through those states only in the correct order."}),"\n",(0,s.jsx)(t.pre,{children:(0,s.jsx)(t.code,{className:"language-scala",metastring:"file=./main/scala/workflow4s/example/docs/pullrequest/PullRequestWorkflow.scala start=start_events end=end_events",children:"sealed trait PREvent\nobject PREvent {\n  case class Created(commit: String)          extends PREvent\n  case class Checked(pipelineResults: String) extends PREvent\n  case class Reviewed(approved: Boolean)      extends PREvent\n}\n"})}),"\n",(0,s.jsx)(t.p,{children:"Workflows4s is built on the idea of event-sourcing, and each non-deterministic action\n(e.g. IO) is memoized through an event. Those events are used to recompute the workflow state upon recovery."}),"\n",(0,s.jsx)(t.pre,{children:(0,s.jsx)(t.code,{className:"language-scala",metastring:"file=./main/scala/workflow4s/example/docs/pullrequest/PullRequestWorkflow.scala start=start_signals end=end_signals",children:"object Signals {\n  val createPR: SignalDef[CreateRequest, Unit] = SignalDef()\n  val reviewPR: SignalDef[ReviewRequest, Unit] = SignalDef()\n  case class CreateRequest(commit: String)\n  case class ReviewRequest(approve: Boolean)\n}\n"})}),"\n",(0,s.jsx)(t.p,{children:"Signals are the API of a workflow, they allow delivering information into the workflow and get some response back. In\nour example, we don't leverage the response part."}),"\n",(0,s.jsx)(t.pre,{children:(0,s.jsx)(t.code,{className:"language-scala",metastring:"file=./main/scala/workflow4s/example/docs/pullrequest/PullRequestWorkflow.scala start=start_error end=end_error",children:"sealed trait PRError\nobject PRError {\n  case object CommitNotFound extends PRError\n  case object PipelineFailed extends PRError\n  case object ReviewRejected extends PRError\n}\n"})}),"\n",(0,s.jsx)(t.p,{children:"Workflows4s supports short-circuiting operations with domain errors. The mechanism is similar to Either or bi-functor IO\nand different parts of the workflow can use different errors. In this example, we use just one. All errors will be\nvisible in type signatures."}),"\n",(0,s.jsx)(t.p,{children:"Now that we have it covered, we can plug state and events into our context and start defining the steps."}),"\n",(0,s.jsx)(t.pre,{children:(0,s.jsx)(t.code,{className:"language-scala",metastring:"file=./main/scala/workflow4s/example/docs/pullrequest/PullRequestWorkflow.scala start=start_context end=end_context",children:"object Context extends WorkflowContext {\n  override type Event = PREvent\n  override type State = PRState\n}\nimport Context.*\n"})}),"\n",(0,s.jsx)(t.p,{children:"Our first step creates the PR reacting to the previously defined signal."}),"\n",(0,s.jsx)(t.pre,{children:(0,s.jsx)(t.code,{className:"language-scala",metastring:"file=./main/scala/workflow4s/example/docs/pullrequest/PullRequestWorkflow.scala start=start_steps_1 end=end_steps_1",children:"val createPR: WIO[Any, PRError.CommitNotFound.type, PRState.Initiated] =\n  WIO\n    .handleSignal(Signals.createPR)\n    .using[Any]\n    .purely((in, req) => PREvent.Created(req.commit))\n    .handleEventWithError((in, evt) =>\n      if (evt.commit.length > 8) Left(PRError.CommitNotFound)\n      else Right(PRState.Initiated(evt.commit)),\n    )\n    .voidResponse\n    .autoNamed()\n"})}),"\n",(0,s.jsx)(t.p,{children:"We handled the signal without side effects and added dummy validation logic in the event handler. In real life, we could\ndo the lookup in git to verify commits existence and emit different events based on the outcome of that lookup."}),"\n",(0,s.jsxs)(t.p,{children:[(0,s.jsx)(t.code,{children:"voidResponse"})," produces unit value as signal response. ",(0,s.jsx)(t.code,{children:"autoNamed"})," fills the name of the step based on the variable\nname."]}),"\n",(0,s.jsx)(t.p,{children:"Step definitions are verbose by design, because they are not lightweight things. Each step in the workflow adds\ncomplexity and mental load. The workflow should have as few steps as possible."}),"\n",(0,s.jsx)(t.p,{children:"In the next 2 steps, we run a fake side-effectful computation and handle the review signal."}),"\n",(0,s.jsx)(t.pre,{children:(0,s.jsx)(t.code,{className:"language-scala",metastring:"file=./main/scala/workflow4s/example/docs/pullrequest/PullRequestWorkflow.scala start=start_steps_2 end=end_steps_2",children:'val runPipeline: WIO[PRState.Initiated, PRError.PipelineFailed.type, PRState.Checked] =\n  WIO\n    .runIO[PRState.Initiated](in => IO(PREvent.Checked("<Some tests results>")))\n    .handleEventWithError((in, evt) =>\n      if (evt.pipelineResults.contains("error")) Left(PRError.PipelineFailed)\n      else Right(PRState.Checked(in.commit, evt.pipelineResults)),\n    )\n    .autoNamed\n\nval awaitReview: WIO[PRState.Checked, PRError.ReviewRejected.type, PRState.Reviewed] =\n  WIO\n    .handleSignal(Signals.reviewPR)\n    .using[PRState.Checked]\n    .purely((in, req) => PREvent.Reviewed(req.approve))\n    .handleEventWithError((in, evt) =>\n      if (evt.approved) Right(PRState.Reviewed(in.commit, in.pipelineResults, evt.approved))\n      else Left(PRError.ReviewRejected),\n    )\n    .voidResponse\n    .autoNamed()\n'})}),"\n",(0,s.jsx)(t.p,{children:"With this being done, we can finish the workflow."}),"\n",(0,s.jsx)(t.pre,{children:(0,s.jsx)(t.code,{className:"language-scala",metastring:"file=./main/scala/workflow4s/example/docs/pullrequest/PullRequestWorkflow.scala start=start_steps_3 end=end_steps_3",children:"val mergePR: WIO[PRState.Reviewed, Nothing, PRState.Merged]   =\n  WIO.pure[PRState.Reviewed].make(in => in).autoNamed()\nval closePR: WIO[(PRState, PRError), Nothing, PRState.Closed] =\n  WIO.pure[(PRState, PRError)].make((state, err) => PRState.Closed(state, err)).autoNamed()\n\nval workflow: WIO[Any, Nothing, PRState] = (\n  createPR >>>\n    runPipeline >>>\n    awaitReview >>>\n    mergePR\n).handleErrorWith(closePR)\n"})}),"\n",(0,s.jsx)(t.p,{children:"Done!\nWe defined last to simple steps as pure deterministic computations and composed the steps exactly the same way as in the\ndraft."}),"\n",(0,s.jsx)(t.p,{children:"Let's generate the diagram again."}),"\n",(0,s.jsx)(t.pre,{children:(0,s.jsx)(t.code,{className:"language-scala",metastring:"file=./main/scala/workflow4s/example/docs/pullrequest/PullRequestWorkflow.scala start=start_render end=end_render",children:'val bpmnModel = BPMNConverter.convert(workflow.getModel, "process")\nBpmn.writeModelToFile(new File(s"pr.bpmn").getAbsoluteFile, bpmnModel)\n'})}),"\n",(0,s.jsx)(t.p,{children:(0,s.jsx)(t.img,{alt:"run-io.svg",src:n(1941).A+"",width:"1176",height:"318"})}),"\n",(0,s.jsx)(t.p,{children:"Has anything changed? Yes, because the error names are now generated automatically from the defined ADT. Otherwise, its\nsame process we defined initially."}),"\n",(0,s.jsx)(t.h2,{id:"running-the-workflow",children:"Running the workflow"}),"\n",(0,s.jsxs)(t.p,{children:["Let's now see how to run the workflow. To do that, we need a ",(0,s.jsx)(t.a,{href:"runtimes",children:"runtime"}),". For simplicity\u2019s sake, we will go with\nsynchronous in-memory runtime."]}),"\n",(0,s.jsx)(t.pre,{children:(0,s.jsx)(t.code,{className:"language-scala",metastring:"file=./main/scala/workflow4s/example/docs/pullrequest/PullRequestWorkflow.scala start=start_execution end=end_execution",children:'import cats.effect.unsafe.implicits.global\nval wfInstance = InMemorySyncRuntime.runWorkflow[Context.type, PRState.Empty.type](\n  behaviour = workflow,\n  state = PRState.Empty\n)\n\nwfInstance.deliverSignal(Signals.createPR, Signals.CreateRequest("some-sha"))\nprintln(wfInstance.queryState())\n// Checked(some-sha,<Some tests results>)\n\nwfInstance.deliverSignal(Signals.reviewPR, Signals.ReviewRequest(approve = false))\nprintln(wfInstance.queryState())\n// Closed(Checked(some-sha,<Some tests results>),ReviewRejected)\n'})}),"\n",(0,s.jsx)(t.p,{children:"And that's pretty much it. Runtime provides us a way to interact with a workflow through delivering signals and querying\nthe state."}),"\n",(0,s.jsx)(t.p,{children:"There is one more thing which we can do which is recovering the workflow."}),"\n",(0,s.jsx)(t.h2,{id:"recovering-the-workflow",children:"Recovering the workflow"}),"\n",(0,s.jsx)(t.p,{children:"This is usually a responsibility of the runtime to fetch the events and reconstruct instance from them, but because we\nare using in-memory runtime here, we can do this programtically."}),"\n",(0,s.jsx)(t.pre,{children:(0,s.jsx)(t.code,{className:"language-scala",metastring:"file=./main/scala/workflow4s/example/docs/pullrequest/PullRequestWorkflow.scala start=start_recovery end=end_recovery",children:"val recoveredInstance = InMemorySyncRuntime.runWorkflow[Context.type, PRState.Empty.type](\n  workflow,\n  PRState.Empty,\n  events = wfInstance.getEvents,\n)\nassert(wfInstance.queryState() == recoveredInstance.queryState())\n"})}),"\n",(0,s.jsx)(t.p,{children:"We created a new instance and provided the initial events taken from the previous instance. The workflow processed them\nand recovered the state without executing any side-effectful operations."}),"\n",(0,s.jsxs)(t.p,{children:["You can find the whole\ncode ",(0,s.jsx)(t.a,{href:"https://github.com/business4s/workflows4s/tree/main/workflows4s-example/src/main/scala/workflow4s/example/docs/pullrequest",children:"here"}),"."]})]})}function h(e={}){const{wrapper:t}={...(0,a.R)(),...e.components};return t?(0,s.jsx)(t,{...e,children:(0,s.jsx)(c,{...e})}):c(e)}},9207:(e,t,n)=>{n.d(t,{A:()=>s});const s=n.p+"assets/images/pull-request-draft-270d95441573328f58946f44c97c2fc1.svg"},1941:(e,t,n)=>{n.d(t,{A:()=>s});const s=n.p+"assets/images/pull-request-2f6e1f82dd463fda6b466bf6e7e0967a.svg"},8453:(e,t,n)=>{n.d(t,{R:()=>o,x:()=>i});var s=n(6540);const a={},r=s.createContext(a);function o(e){const t=s.useContext(r);return s.useMemo((function(){return"function"==typeof e?e(t):{...t,...e}}),[t,e])}function i(e){let t;return t=e.disableParentContext?"function"==typeof e.components?e.components(a):e.components||a:o(e.components),s.createElement(r.Provider,{value:t},e.children)}}}]);