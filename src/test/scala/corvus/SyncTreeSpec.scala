package corvus

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import corvus.sync_tree._
import scala.util.Random

class SyncTreeSpec extends AnyFlatSpec {
  implicit private val p: CorvusConfig = CorvusConfig()
  private val width = p.syncTreeConfig.flagWidth
  private val factor = p.syncTreeConfig.syncTreeFactor
  private val treeDepth = {
    var leaves = 1
    var depth = 0
    while (leaves < p.numSCore) {
      leaves *= factor
      depth += 1
    }
    depth.max(1)
  }
  private val dangling = (BigInt(1) << width) - 1
  private val pendingState = BigInt(0)
  private val rand = new Random(0L)

  private def nextState(): Int = rand.nextInt(1 << width)
  private def nextValidState(): Int = {
    var candidate = nextState()
    while (candidate == dangling || candidate == pendingState) {
      candidate = nextState()
    }
    candidate
  }
  private def nextDistinctState(base: Int): Int = {
    var candidate = nextState()
    val mask = (1 << width) - 1
    while (candidate == base) {
      candidate = (candidate + 1) & mask
    }
    candidate
  }

  behavior of "SyncTree"

  it should "broadcast master state to every slave" in {
    simulate(new SyncTree) { c =>
      c.reset.poke(true.B)
      c.clock.step()
      c.reset.poke(false.B)

      val masterValue = nextState()
      c.io.masterIn.poke(masterValue.U(width.W))
      for (i <- 0 until p.numSCore) {
        c.io.slaveIn(i).poke(nextState().U(width.W))
      }

      c.clock.step(treeDepth)

      for (i <- 0 until p.numSCore) {
        c.io.slaveOut(i).expect(masterValue.U(width.W))
      }
    }
  }

  it should "merge unanimous slave states back to the master" in {
    simulate(new SyncTree) { c =>
      c.reset.poke(true.B)
      c.clock.step()
      c.reset.poke(false.B)

      val agreedValue = nextValidState()
      c.io.masterIn.poke(nextState().U(width.W))
      for (i <- 0 until p.numSCore) {
        c.io.slaveIn(i).poke(agreedValue.U(width.W))
      }

      c.clock.step(treeDepth)

      c.io.masterOut.expect(agreedValue.U(width.W))
    }
  }

  it should "ignore dangling slaves when merging" in {
    simulate(new SyncTree) { c =>
      c.reset.poke(true.B)
      c.clock.step()
      c.reset.poke(false.B)

      val leaderValue = nextValidState()
      c.io.masterIn.poke(nextState().U(width.W))
      c.io.slaveIn(0).poke(leaderValue.U(width.W))
      for (i <- 1 until p.numSCore) {
        c.io.slaveIn(i).poke(dangling.U(width.W))
      }

      c.clock.step(treeDepth)

      c.io.masterOut.expect(leaderValue.U(width.W))
    }
  }

  it should "propagate DANGLING when all slaves are dangling" in {
    simulate(new SyncTree) { c =>
      c.reset.poke(true.B)
      c.clock.step()
      c.reset.poke(false.B)

      c.io.masterIn.poke(nextState().U(width.W))
      for (i <- 0 until p.numSCore) {
        c.io.slaveIn(i).poke(dangling.U(width.W))
      }

      c.clock.step(treeDepth)

      c.io.masterOut.expect(dangling.U(width.W))
    }
  }

  it should "raise PENDING when slaves disagree" in {
    simulate(new SyncTree) { c =>
      c.reset.poke(true.B)
      c.clock.step()
      c.reset.poke(false.B)

      val baseValue = nextValidState()
      val disagreeValue = nextDistinctState(baseValue)

      c.io.masterIn.poke(nextState().U(width.W))
      c.io.slaveIn(0).poke(baseValue.U(width.W))
      c.io.slaveIn(1).poke(disagreeValue.U(width.W))
      for (i <- 2 until p.numSCore) {
        c.io.slaveIn(i).poke(baseValue.U(width.W))
      }

      c.clock.step(treeDepth)

      c.io.masterOut.expect(pendingState.U(width.W))
    }
  }
}
