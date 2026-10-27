// ProcessScheduler.scala
// Scala 3 simulation of a simple process manager / scheduler using the provided ProcessControlBlock model.

package config

import scala.collection.mutable

final case class MemoryLimits(base: Long, limit: Long)

final case class SchedulingInfo(
    priority: Int = 0,
    cpuTimeUsed: Long = 0L,
    lastScheduledAt: Long = 0L
)

final case class ProcessControlBlock(
    pid: Int,
    processState: ProcessControlBlock.ProcessState,
    programCounter: Long,
    registers: Map[String, Long],
    schedulingInfo: SchedulingInfo,
    memoryLimits: MemoryLimits,
    openFiles: List[String]
)

object ProcessControlBlock {
  enum ProcessState {
    case New, Ready, Running, Waiting, Terminated
  }

  def create(pid: Int, priority: Int = 0): ProcessControlBlock =
    ProcessControlBlock(
      pid = pid,
      processState = ProcessState.New,
      programCounter = 0L,
      registers = Map.empty,
      schedulingInfo = SchedulingInfo(priority = priority),
      memoryLimits = MemoryLimits(0L, 0L),
      openFiles = Nil
    )
}

/** A simple scheduler that supports two algorithms:
  *   - Round Robin (quantum-based)
  *   - Priority Scheduling (non-preemptive or preemptive depending on flag)
  *
  * The Scheduler maintains internal mutable queues for Ready and Waiting
  * processes and a map of all PCBs by pid. It provides an easy simulation
  * interface: createProcess, tick (advance one time unit), runForTicks,
  * block/unblock processes, and terminate.
  */
class Scheduler(
    var schedulingQuantum: Int = 4,
    var preemptivePriority: Boolean = true
) {
  import ProcessControlBlock.ProcessState

  private val nextPid = Iterator.from(1)
  private val pcbs = mutable.Map.empty[Int, ProcessControlBlock]

  // Ready queue implemented as a FIFO for RR, but when using priority scheduling
  // we will select the highest-priority ready process instead.
  private val readyQueue = mutable.Queue.empty[Int]
  private val waitingQueue = mutable.Set.empty[Int]

  // Currently running PID (if any) and remaining quantum for round-robin
  private var currentPid: Option[Int] = None
  private var quantumLeft: Int = schedulingQuantum

  private var clock: Long = 0L

  // --- Process lifecycle management ---
  def createProcess(priority: Int = 0): Int = {
    val pid = nextPid.next()
    val pcb = ProcessControlBlock.create(pid, priority)
    pcbs(pid) = pcb
    // Newly created processes become Ready
    val readyPcb = pcb.copy(processState = ProcessState.Ready)
    pcbs(pid) = readyPcb
    readyQueue.enqueue(pid)
    pid
  }

  def terminateProcess(pid: Int): Unit =
    pcbs.get(pid).foreach { pcb =>
      pcbs(pid) = pcb.copy(processState = ProcessState.Terminated)
      if (currentPid.contains(pid)) currentPid = None
      readyQueue.dequeueAll(_ == pid)
      waitingQueue.remove(pid)
    }

  def blockProcess(pid: Int): Unit =
    pcbs.get(pid).foreach { pcb =>
      pcbs(pid) = pcb.copy(processState = ProcessState.Waiting)
      readyQueue.dequeueAll(_ == pid)
      waitingQueue.add(pid)
      if (currentPid.contains(pid)) {
        currentPid = None
        quantumLeft = schedulingQuantum
      }
    }

  def unblockProcess(pid: Int): Unit =
    if (waitingQueue.contains(pid)) {
      waitingQueue.remove(pid)
      pcbs.get(pid).foreach { pcb =>
        pcbs(pid) = pcb.copy(processState = ProcessState.Ready)
        readyQueue.enqueue(pid)
      }
    }

  // --- Scheduling helpers ---
  private def chooseNextProcess(): Option[Int] =
    if (readyQueue.isEmpty) None
    else if (preemptivePriority)
      // choose the ready pid with highest priority (lower number = higher priority)
      Some(readyQueue.minBy(pid => pcbs(pid).schedulingInfo.priority))
    else
      // round-robin style FIFO
      Some(readyQueue.front)

  private def removeFromReady(pid: Int): Unit = readyQueue.dequeueAll(_ == pid)

  // --- Simulation tick ---
  /** Advance the scheduler by one time unit (tick).
    *   1. If there's no running process, select one. 2. Execute one 'unit' of
    *      CPU time on current process (increment program counter). 3. Update
    *      scheduling info and quantum. If quantum expires, preempt (RR) or
    *      continue depending on algorithm.
    */
  def tick(): Unit = {
    clock += 1

    // Select a process if none running
    if (currentPid.isEmpty) {
      chooseNextProcess().foreach { pid =>
        startRunning(pid)
      }
    }

    currentPid.foreach { pid =>
      // execute one unit
      val pcb = pcbs(pid)
      val updated = pcb.copy(
        programCounter = pcb.programCounter + 1,
        schedulingInfo = pcb.schedulingInfo.copy(
          cpuTimeUsed = pcb.schedulingInfo.cpuTimeUsed + 1,
          lastScheduledAt = clock
        )
      )
      pcbs(pid) = updated

      // decrement quantum for RR mode
      if (!preemptivePriority) {
        quantumLeft -= 1
        if (quantumLeft <= 0) {
          // time slice expired: move running process to ready tail
          stopRunning(pid)
        }
      } else {
        // in priority mode, optionally preempt if a higher-priority process arrived
        if (readyQueue.nonEmpty) {
          val best = readyQueue.minBy(p => pcbs(p).schedulingInfo.priority)
          if (
            pcbs(best).schedulingInfo.priority < pcbs(
              pid
            ).schedulingInfo.priority
          ) {
            // preempt current
            stopRunning(pid)
          }
        }
      }
    }
  }

  private def startRunning(pid: Int): Unit = {
    removeFromReady(pid)
    currentPid = Some(pid)
    quantumLeft = schedulingQuantum
    pcbs.get(pid).foreach { pcb =>
      pcbs(pid) = pcb.copy(processState = ProcessState.Running)
    }
  }

  private def stopRunning(pid: Int): Unit = {
    if (currentPid.contains(pid)) currentPid = None
    // only requeue if not terminated or waiting
    pcbs.get(pid).foreach { pcb =>
      if (
        pcb.processState != ProcessState.Terminated && pcb.processState != ProcessState.Waiting
      ) {
        pcbs(pid) = pcb.copy(processState = ProcessState.Ready)
        readyQueue.enqueue(pid)
      }
    }
    quantumLeft = schedulingQuantum
  }

  // --- Utilities for simulation control ---
  def runForTicks(n: Int): Unit =
    for (_ <- 1 to n) tick()

  def ioComplete(pid: Int): Unit = unblockProcess(pid)

  def setPriority(pid: Int, newPriority: Int): Unit =
    pcbs.get(pid).foreach { pcb =>
      pcbs(pid) = pcb
        .copy(schedulingInfo = pcb.schedulingInfo.copy(priority = newPriority))
    }

  def info(pid: Int): Option[ProcessControlBlock] = pcbs.get(pid)

  def dumpState(): String = {
    val running = currentPid
      .flatMap(pcbs.get)
      .map(p => s"PID ${p.pid} Running (pc=${p.programCounter})")
      .getOrElse("<idle>")
    val ready = readyQueue
      .map(pid => s"$pid(p=${pcbs(pid).schedulingInfo.priority})")
      .mkString(", ")
    val waiting = waitingQueue
      .map(pid => s"$pid(p=${pcbs(pid).schedulingInfo.priority})")
      .mkString(", ")
    s"clock=$clock, running=$running, ready=[$ready], waiting=[$waiting]"
  }
}

// --- Usage example / simple test harness ---
object SchedulerDemo extends App {
  val sched = new Scheduler(schedulingQuantum = 3, preemptivePriority = false)

  val p1 = sched.createProcess(priority = 5) // low priority
  val p2 = sched.createProcess(priority = 1) // high priority
  val p3 = sched.createProcess(priority = 3)

  println("Initial:")
  println(sched.dumpState())

  sched.runForTicks(5)
  println("After 5 ticks:")
  println(sched.dumpState())

  // block p2 (simulate IO)
  sched.blockProcess(p2)
  println(s"Blocked $p2")
  sched.runForTicks(4)
  println(sched.dumpState())

  // IO complete for p2
  sched.ioComplete(p2)
  println(s"Unblocked $p2")
  sched.runForTicks(6)
  println(sched.dumpState())

  // terminate p1
  sched.terminateProcess(p1)
  println(s"Terminated $p1")
  println(sched.dumpState())
}
