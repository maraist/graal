/*
 * Copyright (c) 2016, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.graal.lir.profiling;

import static jdk.vm.ci.code.ValueUtil.isRegister;
import static jdk.vm.ci.code.ValueUtil.isStackSlot;

import com.oracle.graal.debug.GraalError;
import com.oracle.graal.lir.StandardOp.LoadConstantOp;
import com.oracle.graal.lir.StandardOp.MoveOp;
import com.oracle.graal.lir.StandardOp.ValueMoveOp;

import jdk.vm.ci.meta.AllocatableValue;
import jdk.vm.ci.meta.Value;

enum MoveType {
    REG2REG("Reg", "Reg"),
    STACK2REG("Reg", "Stack"),
    CONST2REG("Reg", "Const"),
    REG2STACK("Stack", "Reg"),
    CONST2STACK("Stack", "Const"),
    STACK2STACK("Stack", "Stack");

    private final String name;

    MoveType(String dst, String src) {
        this.name = src + '2' + dst;
    }

    @Override
    public String toString() {
        return name;
    }

    public static MoveType get(MoveOp move) {
        AllocatableValue dst = move.getResult();
        Value src = null;
        if (move instanceof LoadConstantOp) {
            if (isRegister(dst)) {
                return CONST2REG;
            } else if (isStackSlot(dst)) {
                return CONST2STACK;
            }
        } else if (move instanceof ValueMoveOp) {
            src = ((ValueMoveOp) move).getInput();
            if (isRegister(dst)) {
                if (isRegister(src)) {
                    return REG2REG;
                } else if (isStackSlot(src)) {
                    return STACK2REG;
                }
            } else if (isStackSlot(dst)) {
                if (isRegister(src)) {
                    return REG2STACK;
                } else if (isStackSlot(src)) {
                    return STACK2STACK;
                }
            }
        }
        throw GraalError.shouldNotReachHere(String.format("Unrecognized Move: %s dst=%s, src=%s", move, dst, src));
    }
}