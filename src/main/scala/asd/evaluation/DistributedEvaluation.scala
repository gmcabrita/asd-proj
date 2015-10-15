package asd.evaluation

import asd.message._
import asd.rand.Zipf
import asd._

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Props
import akka.pattern.ask

import akka.util.Timeout
import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Random

import scala.concurrent.ExecutionContext.Implicits.global
import com.typesafe.config.ConfigFactory

import scala.reflect.ClassTag
import scala.reflect._

class DistributedEvaluation(number_of_keys: Int, number_of_clients: Int, number_of_servers: Int, quorum: Int, degree_of_replication: Int, seed: Int, linearizable: Boolean, max_operations: Int, faults: Int) extends Actor {
  val zipf = new Zipf(number_of_keys, seed)
  val r = new Random(seed)
  implicit val system = ActorSystem("EVAL")
  val remoteSystem = ActorSystem("REMOTE", ConfigFactory.load("main"))
  implicit val timeout = Timeout(10 seconds)

  val servers = (1 to number_of_servers).toVector.map(i => {
    if (i % 2 == 0) system.actorOf(Props[Server])
    else remoteSystem.actorOf(Props[Server])
  })
  val clients: Vector[ActorRef] = (1 to number_of_clients).toVector.map(_ => {
    if (linearizable) system.actorOf(Props(new ClientNonBlocking(servers.toList, quorum, degree_of_replication)))
    else system.actorOf(Props(new ClientNonBlockingNonLinearizable(servers.toList, quorum, degree_of_replication)))
  })

  // fault injection
  val fault_rand = new Random()
  r.shuffle(0 to number_of_clients - 1).take(faults).foreach(i => system.stop(servers(i)))

  var operations = max_operations
  var reads: Long = 0
  var writes: Long = 0

  var begin: Long = 0
  var end: Long = 0

  var run: Int = 0
  var rw_ratio = (90, 10)

  def continue(actr: ActorRef) = {
     operations -= 1

    if (operations == 0) {
      end = System.nanoTime
      println("reads: " + reads)
      println("writes: " + writes)
      println("elapsed time: " + (end - begin)/1e6+"ms")

      rw_ratio = (rw_ratio._1 - 40, rw_ratio._2 + 40)
      begin = System.nanoTime
      operations = max_operations
      reads = 0
      writes = 0

      run += 1
      if (run == 3) sys.exit(0)
    }

    actr ! gen_op()
  }

  def gen_op() = {
    val float = r.nextFloat()
    val key = zipf.nextZipf().toString
    if (float > (rw_ratio._2 / 100f)) { // read
      reads += 1
      Get(key)
    } else { // write
      val value = r.nextString(16)
      writes += 1
      Put(key, value)
    }
  }

  def receive = {
    case Start => {
      begin = System.nanoTime
      operations -= number_of_clients
      clients.foreach(_ ! gen_op())
    }
    case _ => continue(sender)
  }
}