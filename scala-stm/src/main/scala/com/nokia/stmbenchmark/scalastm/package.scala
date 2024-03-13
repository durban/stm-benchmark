/*
 * © 2023-2024 Nokia
 * Licensed under the Apache License 2.0
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nokia.stmbenchmark

import cats.free.Free

package object scalastm {

  type WrStm[A] = Free[WrStm.WrStmA, A]
}
