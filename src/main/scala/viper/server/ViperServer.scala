/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package viper.server


import org.reactivestreams.Publisher

import scala.language.postfixOps
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.collection.mutable
import scala.util.{Failure, Success, Try}
import akka.{Done, NotUsed}
import akka.pattern.ask
import akka.util.Timeout
import akka.actor.{Actor, ActorRef, ActorSystem, PoisonPill, Props}
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.stream.scaladsl.{Keep, Sink, Source, SourceQueueWithComplete}
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives._
import viper.server.ViperServerProtocol._
import viper.server.ViperIDEProtocol._
import viper.silver.reporter
import viper.silver.reporter._

import scala.util.Try


object ViperServerRunner {

  import ViperRequests._

  private var _config: ViperConfig = _
  final def config: ViperConfig = _config

  implicit val system = ActorSystem("Main")
  implicit val materializer = ActorMaterializer()


  // --- Actor: Terminator ---

  private var _term_actor: ActorRef = _

  object Terminator {
    case object Exit
    case class WatchJob(jid: Int)

    def props(bindingFuture: Future[Http.ServerBinding]): Props = Props(new Terminator(bindingFuture))
  }

  class Terminator(bindingFuture: Future[Http.ServerBinding]) extends Actor {
    implicit val executionContext = system.dispatcher

    override def receive = {
      case Terminator.Exit =>
        bindingFuture
          .flatMap(_.unbind()) // trigger unbinding from the port
          .onComplete(_ => system.terminate()) // and shutdown when done
      case Terminator.WatchJob(jid) =>
        _job_handles.get(jid) match {
          case Some(handle@JobHandle(controller, queue, _)) =>
            val queue_completion_future: Future[Done] = queue.watchCompletion()
            queue_completion_future.onSuccess({ case _ =>
              _job_handles -= jid
              println(s"Terminator deleted job #${jid}")
            })
          case _ =>
            println(s"Terminator: job #${jid} does not exist anymore.")
        }
    }
  }


  // --- Actor: MainActor ---

  // We can potentially have more than one verification task at the same time.
  // A verification task is distinguished via the corresponding ActorRef,
  //  as well as its unique job_id.

  case class JobHandle(controller_actor: ActorRef,
                       queue: SourceQueueWithComplete[Message],
                       publisher: Publisher[Message])

  private var _job_handles = mutable.Map[Int, JobHandle]()
  private var _next_job_id: Int = 0
  private val _max_active_jobs: Int = 3

  def new_jobs_allowed = _job_handles.size < _max_active_jobs

  // (See model description in ViperServerProtocol.scala)

  object MainActor {
    def props(id: Int): Props = Props(new MainActor(id))
  }

  class MainActor(private val id: Int) extends Actor {

    implicit val executionContext = system.dispatcher

    private var _verificationTask: Thread = null
    private var _args: List[String] = null

    // blocking
    private def interrupt: Boolean = {
      if (_verificationTask != null && _verificationTask.isAlive) {
        _verificationTask.interrupt()
        _verificationTask.join()
        println(s"Job #${id} has been successfully interrupted.")
        return true
      }
      else return false
    }

    def receive = {
      case Stop(call_back_needed) =>
        val did_I_interrupt = interrupt
        if (call_back_needed) {
          // If a callback is expected, then the caller must decide when to kill the actor.
          if (did_I_interrupt) {
            sender ! s"Job #${id} has been successfully interrupted."
          } else {
            sender ! s"Job #${id} has already been finalized."
          }
        }
      case Verify(args) =>
        if (_verificationTask != null && _verificationTask.isAlive) {
          _args = args
          _verificationTask.interrupt()
          _verificationTask.join()
        }
        _verificationTask = null
        verify(args)
      case msg =>
        throw new Exception("Main Actor: unexpected message received: " + msg)
    }

    private def verify(args: List[String]): Unit = {
      assert(_verificationTask == null)

      // TODO: reimplement with [[SourceQueue]]s and backpressure strategies.

      // The maximum number of messages in the reporter's message buffer is 1000.
      val (queue, publisher) = Source.queue[Message](1000, OverflowStrategy.backpressure).toMat(Sink.asPublisher(false))(Keep.both).run()

      val my_reporter = system.actorOf(ReporterActor.props(queue), s"reporter_$id")

      _verificationTask = new Thread(new VerificationWorker(my_reporter, args))
      _verificationTask.start()

      //println(s"Client #$id disconnected")

      assert(_job_handles.get(id).isEmpty)
      _job_handles(id) = JobHandle(self, queue, publisher)
      _next_job_id = _next_job_id + 1
    }
  }


  // --- Actor: ReporterActor ---

  object ReporterActor {
    case object ClientRequest
    case class ServerRequest(msg: reporter.Message)
    case object FinalServerRequest

    def props(queue: SourceQueueWithComplete[Message]): Props = Props(new ReporterActor(queue))
  }

  class ReporterActor(queue: SourceQueueWithComplete[Message]) extends Actor {

    def receive = {
      case ReporterActor.ClientRequest =>
      case ReporterActor.ServerRequest(msg) =>
        queue.offer(msg)
      case ReporterActor.FinalServerRequest =>
        queue.complete()
        println(s"Job has been successfully completed.")
        self ! PoisonPill
      case _ =>
    }
  }

  def main(args: Array[String]): Unit = {

    implicit val executionContext = system.dispatcher

    try {
      parseCommandLine(args)

    } catch { case e: Throwable =>
      println(s"Cannot parse CMD arguments: $e")
      sys.exit(1)
    }

    ViperCache.initialize(config.backendSpecificCache())

    val routes = {
      path("exit") {
        get {
          val interrupt_future_list: List[Future[String]] = _job_handles map { case (jid, handle@JobHandle(actor, _, _)) =>
            implicit val askTimeout = Timeout(5000 milliseconds)
            (actor ? Stop(true)).mapTo[String]
          } toList
          val overall_interrupt_future: Future[List[String]] = Future.sequence(interrupt_future_list)

          onComplete(overall_interrupt_future) { (err: Try[List[String]]) =>
            err match {
              case Success(_) =>
                _term_actor ! Terminator.Exit
                complete( ServerStopConfirmed("shutting down...") )
              case Failure(err) =>
                println(s"Interrupting one of the verification threads timed out: ${err}")
                _term_actor ! Terminator.Exit
                complete( ServerStopConfirmed("forcibly shutting down...") )
            }
          }
        }
      }
    } ~ path("verify") {
      post {
        entity(as[VerificationRequest]) { r =>
          if (new_jobs_allowed) {
            val id = _next_job_id
            val main_actor = system.actorOf(MainActor.props(id), s"main_actor_$id")
            var arg_list = getArgListFromArgString(r.arg)
            main_actor ! ViperServerProtocol.Verify(arg_list)
            complete( VerificationRequestAccept(id) )

          } else {
            complete( VerificationRequestReject(s"the maximum number of active verification jobs are currently running (${_max_active_jobs}).") )
          }
        }
      }
    } ~ path("verify" / IntNumber) { jid =>
      get {
        _job_handles.get(jid) match {
          case Some(handle) =>
            //Found a job with this jid.
            val src: Source[Message, NotUsed] = Source.fromPublisher(handle.publisher)
            // We do not remove the current entry from [[_job_handles]] because the handle is
            //  needed in order to terminate the job before streaming is completed.
            //  The Terminator actor will delete the entry upon completion of the stream.
            _term_actor ! Terminator.WatchJob(jid)
            complete(src)
          case _ =>
            // Did not find a job with this jid.
            complete( VerificationRequestReject(s"The verification job #$jid does not exist.") )
        }
      }
    } ~ path("discard" / IntNumber) { jid =>
      get {
        _job_handles.get(jid) match {
          case Some(handle) =>
            implicit val askTimeout = Timeout(5 seconds)
            val interrupt_done: Future[String] = (handle.controller_actor ? Stop(true)).mapTo[String]
            onSuccess(interrupt_done) { msg =>
                handle.controller_actor ! PoisonPill // the actor played its part.
                complete( JobDiscardAccept(msg) )
            }
          case _ =>
            // Did not find a job with this jid.
            complete( JobDiscardReject(s"The verification job #$jid does not exist.") )
        }
      }
    }

    val port = viper.server.utility.Sockets.findFreePort
    val bindingFuture: Future[Http.ServerBinding] = Http().bindAndHandle(routes, "localhost", port)

    println(s"ViperServer online at http://localhost:$port")

    _term_actor = system.actorOf(Terminator.props(bindingFuture), "terminator")

  } // method main

  def parseCommandLine(args: Seq[String]) {
    _config = new ViperConfig(args)
    _config.verify()
  }

  private def getArgListFromArgString(arg_str: String): List[String] = {
    val possibly_quoted_string = raw"""[^\s"']+|"[^"]*"|'[^']*'""".r
    val quoted_string = """^["'](.*)["']$""".r
    possibly_quoted_string.findAllIn(arg_str).toList.map {
      case quoted_string(noqt_a) => noqt_a
      case a => a
    }
  }

} // object ViperServerRunner
