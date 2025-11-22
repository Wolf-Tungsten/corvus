package corvus.state_bus

import chisel3._
import chisel3.util._
import corvus.CorvusConfig

class RingNode(implicit p: CorvusConfig) extends Module {
  private val dstWidth = p.stateBusConfig.dstWidth
  private val queueDepth = p.stateBusConfig.ringNodeQueueDepth
  require(queueDepth >= 2, "ringNodeQueueDepth must be at least 2 to allow fromCore injection")

  val io = IO(new Bundle {
    val fromPrev = Flipped(Decoupled(new StateBusPacket))
    val toNext = Decoupled(new StateBusPacket)
    val fromCore = Flipped(Decoupled(new StateBusPacket))
    val toCore = Decoupled(new StateBusPacket)
    val nodeId = Input(UInt(dstWidth.W))
    val hasPayload = Output(Bool())
  })

  val payloadQueue = Module(new Queue(new StateBusPacket, queueDepth))

  // Default connections for queue output and hasPayload flag
  io.toNext <> payloadQueue.io.deq
  io.hasPayload := payloadQueue.io.deq.valid

  // Destination match check
  val prevTargetsThis = io.fromPrev.bits.dst === io.nodeId
  val prevValid = io.fromPrev.valid
  val prevMatch = prevValid && prevTargetsThis
  val prevNeedsEnq = prevValid && !prevTargetsThis

  // Free slots calculation for enforcing >1 space rule on fromCore
  val freeSlots = queueDepth.U - payloadQueue.io.count

  // toCore path for matching packets
  io.toCore.valid := prevMatch
  io.toCore.bits := io.fromPrev.bits

  // Enqueue arbitration: fromPrev has priority
  val coreCanEnq =
    !prevNeedsEnq && (freeSlots > 1.U) && payloadQueue.io.enq.ready
  val coreEnq = io.fromCore.valid && coreCanEnq
  val prevEnq = prevNeedsEnq && payloadQueue.io.enq.ready

  payloadQueue.io.enq.valid := prevEnq || coreEnq
  payloadQueue.io.enq.bits := Mux(prevEnq, io.fromPrev.bits, io.fromCore.bits)

  // Ready signals
  io.fromPrev.ready := Mux(prevTargetsThis, io.toCore.ready, payloadQueue.io.enq.ready)
  io.fromCore.ready := coreCanEnq
}
