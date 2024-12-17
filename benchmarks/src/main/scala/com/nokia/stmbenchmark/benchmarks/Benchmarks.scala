/*
 * © 2023-2024 Nokia
 * Licensed under the Apache License 2.0
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nokia.stmbenchmark
package benchmarks

import scala.annotation.nowarn

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import zio.Task

import org.openjdk.jmh.annotations._

import common.{ Solver, Board }
import catsstm.CatsStmSolver
import zstm.ZstmSolver
import choam.RxnSolver
import scalastm.{ ScalaStmSolver, WrStmSolver }
import sequential.SequentialSolver
import arrowstm.{ KotlinInterop, ArrowStmSolver }

@Fork(value = 3, jvmArgsAppend = Array(
  "-XX:+UseG1GC",
  // "-XX:+UseZGC", "-XX:+ZGenerational",
  // "-XX:+UseShenandoahGC",
  "-Dcats.effect.tracing.mode=NONE",
  "-Ddev.tauri.choam.stats=false",
  "-Dkyo.scheduler.enableTopJMX=false",
))
@Threads(1) // because it runs on a thread-pool
@BenchmarkMode(Array(Mode.AverageTime))
class Benchmarks extends BenchmarksScalaVersionSpecific {

  import Benchmarks._

  @Benchmark
  def baseline(st: BaselineState): Solver.Solution = {
    st.runSolveTask()
  }

  @Benchmark
  def arrowStm(st: KotlinState): Solver.Solution = {
    st.runSolveTask()
  }

  @Benchmark
  def catsStmOnCe(st: CatsStmOnCeState): Solver.Solution = {
    st.runSolveTask()
  }

  @Benchmark
  def catsStmOnZio(st: CatsStmOnZioState): Solver.Solution = {
    st.runSolveTask()
  }

  @Benchmark
  def rxnOnCe(st: RxnOnCeState): Solver.Solution = {
    st.runSolveTask()
  }

  @Benchmark
  def rxnOnZio(st: RxnOnZioState): Solver.Solution = {
    st.runSolveTask()
  }

  @Benchmark
  def scalaStm(st: ScalaStmState): Solver.Solution = {
    st.runSolveTask()
  }

  @Benchmark
  def wrStm(st: WrStmState): Solver.Solution = {
    st.runSolveTask()
  }

  @Benchmark
  def zstm(st: ZstmState): Solver.Solution = {
    st.runSolveTask()
  }
}

object Benchmarks {

  @State(Scope.Benchmark)
  abstract class AbstractState {

    @Param(Array("empty.txt", "four_crosses.txt", "testBoard.txt", "sparselong_mini.txt", "sparseshort_mini.txt"))
    protected[this] var board: String =
      null

    @Param(Array("42"))
    protected[this] var seed: Long =
      0L

    @Param(Array("3", "2", "1", "0"))
    protected[this] var restrict: Int =
      -1

    @Param(Array("-1"))
    protected[this] var repeat: Int =
      -1

    protected[this] var normalizedRepeat: Int =
      -1

    protected[this] val boardRepeatDefaults: Map[String, Int] = Map(
      "empty.txt" -> 100,
      "four_crosses.txt" -> 10,
    ).withDefaultValue(1)

    protected var normalizedBoard: Board.Normalized =
      null

    def runSolveTask(): Solver.Solution

    @Setup
    protected def setup(): Unit = {
      // for ZSTM/kyo-stm/arrow-stm, we want to avoid a
      // CE threadpool existing during the measurement,
      // so we create a separate runtime just for the
      // initialization, and then shut it down:
      val setupRuntime = cats.effect.unsafe.IORuntimeBuilder().build()
      try {
        val b = Board.fromResource[IO](this.board).unsafeRunSync()(setupRuntime)
        val nb = b.normalize(this.seed)
        this.normalizedBoard = this.restrict match {
          case 0 => nb
          case r => nb.restrict(r)
        }
      } finally {
        setupRuntime.shutdown()
      }
      val rep = this.repeat match {
        case -1 =>
          this.boardRepeatDefaults(this.board)
        case n if n > 0 =>
          n
        case n =>
          throw new IllegalArgumentException(s"invalid value for `repeat`: ${n}")
      }
      assert(rep >= 1)
      this.normalizedRepeat = rep
    }
  }

  @State(Scope.Benchmark)
  abstract class IOState extends AbstractState {

    @Param(Array("0")) // 0 means availableProcessors()
    @nowarn("msg=unset private variable")
    private[this] var parLimit: Int =
      -1

    @Param(Array("1"))
    @nowarn("msg=unset private variable")
    private[this] var parLimitMultiplier: Int =
      -1

    private[this] var solveTask: IO[Solver.Solution] =
      null.asInstanceOf[IO[Solver.Solution]]

    protected def mkSolver(parLimit: Int): IO[Solver[IO]]

    final override def runSolveTask(): Solver.Solution = {
      this.unsafeRunSync(this.solveTask)
    }

    private[this] val runtime =
      this.createIoRuntime()

    protected final def unsafeRunSync[A](tsk: IO[A]): A =
      tsk.unsafeRunSync()(this.runtime)

    /** Subclasses may override if they need something different */
    protected def createIoRuntime(): IORuntime = {
      IORuntime.global
    }

    @Setup
    protected override def setup(): Unit = {
      super.setup()
      val pl = this.parLimit match {
        case 0 =>
          Runtime.getRuntime().availableProcessors()
        case pl =>
          pl
      }
      val plm = this.parLimitMultiplier
      val n = pl * plm
      require(n > 0)
      val solver = unsafeRunSync(this.mkSolver(n))
      this.solveTask = IO.cede *> repeatIO(solver.solve(this.normalizedBoard), this.normalizedRepeat)
    }

    private[this] final def repeatIO[A](tsk: IO[A], n: Int): IO[A] = {
      if (n <= 1) tsk
      else tsk.flatMap { _ => repeatIO(tsk, n - 1) }
    }
  }

  // we're not using IOState here, because we don't want the parLimit param:
  @State(Scope.Benchmark)
  class BaselineState extends AbstractState {

    private[this] val runtime =
      cats.effect.unsafe.IORuntime.global

    private[this] var solveTask: IO[Solver.Solution] =
      null.asInstanceOf[IO[Solver.Solution]]

    final override def runSolveTask(): Solver.Solution = {
      this.solveTask.unsafeRunSync()(this.runtime)
    }

    @Setup
    protected override def setup(): Unit = {
      super.setup()
      val solver = SequentialSolver[IO](log = false).unsafeRunSync()(this.runtime)
      this.solveTask = IO.cede *> repeatIO(solver.solve(this.normalizedBoard), this.normalizedRepeat)
    }

    private[this] final def repeatIO[A](tsk: IO[A], n: Int): IO[A] = {
      if (n <= 1) tsk
      else tsk.flatMap { _ => repeatIO(tsk, n - 1) }
    }
  }

  @State(Scope.Benchmark)
  class CatsStmOnCeState extends IOState {

    protected final override def mkSolver(parLimit: Int): IO[Solver[IO]] = {
      CatsStmSolver[IO](
        txnLimit = parLimit.toLong,
        parLimit = parLimit,
        log = false,
      )
    }
  }

  @State(Scope.Benchmark)
  class CatsStmOnZioState extends ZioState {

    protected final override def mkSolver(parLimit: Int): Task[Solver[Task]] = {
      CatsStmSolver[Task](
        txnLimit = parLimit.toLong,
        parLimit = parLimit,
        log = false,
      )(zio.interop.catz.asyncInstance)
    }
  }

  trait RxnStateMixin {

    protected final def createSolver[F[_] : cats.effect.Async](parLimit: Int, strategy: String): F[Solver[F]] = {
      val str = strategy match {
        case "spin" =>
          RxnSolver.spinStrategy
        case "cede" =>
          RxnSolver.cedeStrategy
        case "sleep" =>
          RxnSolver.sleepStrategy
        case x =>
          throw new IllegalArgumentException(s"invalid strategy: ${x}")
      }
      RxnSolver[F](parLimit = parLimit, log = false, strategy = str)
    }
  }

  @State(Scope.Benchmark)
  class RxnOnCeState extends IOState with RxnStateMixin {

    // @Param(Array("spin", "cede", "sleep"))
    protected[this] var strategy: String =
      "sleep"

    protected final override def mkSolver(parLimit: Int): IO[Solver[IO]] = {
      this.createSolver[IO](parLimit, this.strategy)
    }
  }

  @State(Scope.Benchmark)
  class RxnOnZioState extends ZioState with RxnStateMixin {

    // @Param(Array("spin", "cede", "sleep"))
    protected[this] var strategy: String =
      "sleep"

    protected final override def mkSolver(parLimit: Int): Task[Solver[Task]] = {
      this.createSolver[Task](parLimit, this.strategy)(zio.interop.catz.asyncInstance)
    }
  }

  @State(Scope.Benchmark)
  class ScalaStmState extends IOState {

    protected final override def mkSolver(parLimit: Int): IO[Solver[IO]] = {
      ScalaStmSolver[IO](parLimit = parLimit, log = false)
    }
  }

  @State(Scope.Benchmark)
  class WrStmState extends IOState {

    protected final override def mkSolver(parLimit: Int): IO[Solver[IO]] = {
      WrStmSolver[IO](parLimit = parLimit, log = false)
    }
  }

  @State(Scope.Benchmark)
  class KotlinState extends IOState {

    protected final override def createIoRuntime() = {
      KotlinInterop.ioRuntimeFromCoroutineDispatchers()
    }

    protected final override def mkSolver(parLimit: Int): IO[Solver[IO]] = {
      ArrowStmSolver[IO](parLimit = parLimit, log = false)
    }
  }

  @State(Scope.Benchmark)
  abstract class ZioState extends AbstractState {

    @Param(Array("0")) // 0 means availableProcessors()
    @nowarn("msg=unset private variable")
    private[this] var parLimit: Int =
      -1

    @Param(Array("1"))
    @nowarn("msg=unset private variable")
    private[this] var parLimitMultiplier: Int =
      -1

    private[this] val runtime: zio.Runtime[Any] = {
      zio.Runtime(
        zio.ZEnvironment.empty,
        zio.FiberRefs.empty,
        zio.RuntimeFlags.disable(zio.RuntimeFlags.default)(zio.RuntimeFlag.FiberRoots),
      )
    }

    private[this] var solveTask: Task[Solver.Solution] =
      null.asInstanceOf[Task[Solver.Solution]]

    protected def mkSolver(parLimit: Int): Task[Solver[Task]]

    private[this] final def unsafeRunSync[A](task: Task[A]): A = {
      zio.Unsafe.unsafe { implicit u =>
        this.runtime.unsafe.run(task).getOrThrow()
      }
    }

    final override def runSolveTask(): Solver.Solution = {
      unsafeRunSync(this.solveTask)
    }

    @Setup
    protected override def setup(): Unit = {
      super.setup()
      val pl = this.parLimit match {
        case 0 =>
          Runtime.getRuntime().availableProcessors()
        case pl =>
          pl
      }
      val plm = this.parLimitMultiplier
      val n = pl * plm
      require(n > 0)
      val solver = unsafeRunSync(mkSolver(n))
      this.solveTask = zio.ZIO.yieldNow *> repeatZIO(solver.solve(this.normalizedBoard), this.normalizedRepeat)
    }

    private[this] final def repeatZIO[A](tsk: Task[A], n: Int): Task[A] = {
      if (n <= 1) tsk
      else tsk.flatMap { _ => repeatZIO(tsk, n - 1) }
    }
  }

  @State(Scope.Benchmark)
  class ZstmState extends ZioState {
    protected final override def mkSolver(parLimit: Int): Task[Solver[Task]] = {
      ZstmSolver(parLimit = parLimit, log = false)
    }
  }
}
