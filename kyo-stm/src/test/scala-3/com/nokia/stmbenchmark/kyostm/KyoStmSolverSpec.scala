/*
 * © 2023-2025 Nokia
 * Licensed under the Apache License 2.0
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nokia.stmbenchmark
package kyostm

import java.util.concurrent.ThreadLocalRandom

import scala.concurrent.duration._

import kyo.{ <, Abort, Async, Cats => KyoCats, Sync }
import cats.effect.{ IO => CatsIO }
import fs2.Stream

import munit.{ Location, TestOptions }
import munit.FunSuite

import common.{ Board, Solver, MunitUtils }

final class KyoStmSolverSpec extends FunSuite with KyoInteropMunit with MunitUtils {

  final override def munitTimeout =
    180.minutes

  private[this] lazy val solver: Solver[<[*, Async & Abort[Throwable]]] = {
    unsafeRunSyncIO(
      Sync.defer { Runtime.getRuntime().availableProcessors() }.map { numCpu =>
        KyoStmSolver(
          parLimit = numCpu,
          log = false,
        )
      }
    )
  }

  private def testFromResource(
    resourceNameAndOpts: TestOptions,
    restrict: Int = 0,
    expMaxDepth: Int = -1,
    expTotalCost: Int = -1,
  )(implicit loc: Location): Unit = {
    val nameForMunit = if (restrict != 0) {
      s"${resourceNameAndOpts.name} (restrict = ${restrict})"
    } else {
      resourceNameAndOpts.name
    }
    testKyo(resourceNameAndOpts.withName(nameForMunit)) {
      val loadBoard = KyoCats.get(Board.fromResource[CatsIO](resourceNameAndOpts.name))
      loadBoard.map { board =>
        Sync.defer { this.normalizeAndRestrict(board, restrict) }.map { b =>
          solver.solve(b).map { solution =>
            Sync.defer {
              Abort.catching[Throwable] {
                checkSolutionInternal(
                  resourceNameAndOpts,
                  b,
                  solution,
                  expMaxDepth = expMaxDepth,
                  expTotalCost = expTotalCost,
                )
              }
            }
          }
        }
      }
    }
  }

  testKyo("empty.txt") {
    Sync.defer { Board.empty(10, 10).normalize(ThreadLocalRandom.current().nextLong()) }.map { b =>
      solver.solve(b).map { solution =>
        Sync.defer {
          Abort.catching[Throwable] {
            checkSolutionInternal("empty.txt", b, solution)
          }
        }
      }
    }
  }

  // https://github.com/chrisseaton/ruby-stm-lee-demo/blob/master/inputs/minimal.txt
  testKyo("minimal.txt") {
    val s = Stream[CatsIO, String](
      List(
        "B 10 10",
        "P 2 2",
        "P 7 2",
        "P 2 7",
        "P 7 7",
        "J 2 2 7 7",
        "J 7 2 2 7",
        "E",
        "",
      ).mkString("\n")
    )
    KyoCats.get(Board.fromStream(s)).map { board =>
      Sync.defer { board.normalize(ThreadLocalRandom.current().nextLong()) }.map { b =>
        solver.solve(b).map { solution =>
          Sync.defer {
            Abort.catching[Throwable] {
              checkSolutionInternal(
                "minimal.txt".tag(Verbose),
                b,
                solution,
                expMaxDepth = 2,
                expTotalCost = 24,
              )
            }
          }
        }
      }
    }
  }

  testFromResource("four_crosses.txt".tag(Verbose))
  testFromResource("testBoard.txt".tag(Verbose))
  testFromResource("sparseshort_mini.txt")
  testFromResource("sparseshort.txt", restrict = 1)
  testFromResource("sparselong_mini.txt")
  testFromResource("sparselong.txt", restrict = 2)
  testFromResource("mainboard.txt", restrict = 5)
  testFromResource("memboard.txt", restrict = 4)
}
