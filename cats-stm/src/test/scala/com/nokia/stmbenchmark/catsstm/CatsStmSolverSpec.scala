/*
 * © 2023-2024 Nokia
 * Licensed under the Apache License 2.0
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nokia.stmbenchmark
package catsstm

import cats.effect.IO

import common.JvmCeIoSolverSpec
import common.Solver

import munit.Location

final class CatsStmSolverSpec extends JvmCeIoSolverSpec {

  protected override def createSolver: IO[Solver[IO]] = {
    IO { Runtime.getRuntime().availableProcessors() }.flatMap { numCpu =>
      CatsStmSolver[IO](txnLimit = 2 * numCpu, parLimit = numCpu, log = false)
    }
  }

  testFromResource("testBoard.txt", printSolution = true)
  testFromResource("sparseshort.txt")
  // TODO: sparselong.txt (too long)
  // TODO: mainboard.txt (too long)
}
