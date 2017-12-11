/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.codegen.range

import org.jetbrains.kotlin.codegen.ExpressionCodegen
import org.jetbrains.kotlin.codegen.StackValue
import org.jetbrains.kotlin.codegen.generateCallReceiver
import org.jetbrains.kotlin.codegen.generateCallSingleArgument
import org.jetbrains.kotlin.codegen.range.forLoop.ForInDefinitelySafeSimpleProgressionLoopGenerator
import org.jetbrains.kotlin.codegen.range.forLoop.ForInSimpleProgressionLoopGenerator
import org.jetbrains.kotlin.codegen.range.forLoop.ForLoopGenerator
import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.resolve.calls.model.ResolvedCall
import org.jetbrains.kotlin.resolve.constants.*
import org.jetbrains.kotlin.resolve.scopes.receivers.ExpressionReceiver
import org.jetbrains.kotlin.utils.addToStdlib.safeAs

class PrimitiveNumberRangeLiteralRangeValue(
        rangeCall: ResolvedCall<out CallableDescriptor>
) : PrimitiveNumberRangeIntrinsicRangeValue(rangeCall),
        ReversableRangeValue {

    override fun getBoundedValue(codegen: ExpressionCodegen) =
            SimpleBoundedValue(codegen, rangeCall)

    override fun createForLoopGenerator(codegen: ExpressionCodegen, forExpression: KtForExpression): ForLoopGenerator =
            createConstBoundedForInRangeLiteralGenerator(codegen, forExpression) ?:
            ForInSimpleProgressionLoopGenerator.fromBoundedValueWithStep1(codegen, forExpression, getBoundedValue(codegen))

    override fun createForInReversedLoopGenerator(codegen: ExpressionCodegen, forExpression: KtForExpression): ForLoopGenerator =
            createConstBoundedRangeForInReversedRangeLiteralGenerator(codegen, forExpression) ?:
            ForInSimpleProgressionLoopGenerator.fromBoundedValueWithStepMinus1(codegen, forExpression, getBoundedValue(codegen))

    private fun createConstBoundedForInRangeLiteralGenerator(
            codegen: ExpressionCodegen,
            forExpression: KtForExpression
    ): ForLoopGenerator? {
        val endExpression = rangeCall.valueArgumentsByIndex?.run { get(0).arguments[0].getArgumentExpression() } ?: return null
        return createConstBoundedForLoopGenerator(
                codegen, forExpression,
                codegen.generateCallReceiver(rangeCall),
                endExpression,
                1
        )
    }

    private fun createConstBoundedRangeForInReversedRangeLiteralGenerator(
            codegen: ExpressionCodegen,
            forExpression: KtForExpression
    ): ForLoopGenerator? {
        val endExpression = rangeCall.extensionReceiver.safeAs<ExpressionReceiver>()?.expression ?: return null
        return createConstBoundedForLoopGenerator(
                codegen, forExpression,
                codegen.generateCallSingleArgument(rangeCall),
                endExpression,
                -1
        )
    }

    private fun createConstBoundedForLoopGenerator(
            codegen: ExpressionCodegen,
            forExpression: KtForExpression,
            startValue: StackValue,
            endExpression: KtExpression,
            step: Int
    ): ForLoopGenerator? {
        val endConstValue = codegen.getCompileTimeConstant(endExpression).safeAs<IntegerValueConstant<*>>() ?: return null

        return when (endConstValue) {
            is ByteValue -> {
                val endIntValue = endConstValue.value.toInt()
                if (isProhibitedIntConstEndValue(step, endIntValue))
                    null
                else
                    createConstBoundedIntForLoopGenerator(codegen, forExpression, startValue, endIntValue, step)
            }

            is ShortValue -> {
                val endIntValue = endConstValue.value.toInt()
                if (isProhibitedIntConstEndValue(step, endIntValue))
                    null
                else
                    createConstBoundedIntForLoopGenerator(codegen, forExpression, startValue, endIntValue, step)
            }

            is IntValue -> {
                val endIntValue = endConstValue.value
                if (isProhibitedIntConstEndValue(step, endIntValue))
                    null
                else
                    createConstBoundedIntForLoopGenerator(codegen, forExpression, startValue, endIntValue, step)
            }

            is CharValue -> {
                val endCharValue = endConstValue.value
                if (isProhibitedCharConstEndValue(step, endCharValue))
                    null
                else
                    createConstBoundedIntForLoopGenerator(codegen, forExpression, startValue, endCharValue.toInt(), step)
            }

            is LongValue -> {
                val endLongValue = endConstValue.value
                if (isProhibitedLongConstEndValue(step, endLongValue))
                    null
                else
                    createConstBoundedLongForLoopGenerator(codegen, forExpression, startValue, endLongValue, step)
            }

            else -> null
        }
    }

    private fun createConstBoundedIntForLoopGenerator(
            codegen: ExpressionCodegen,
            forExpression: KtForExpression,
            startValue: StackValue,
            endIntValue: Int,
            step: Int
    ): ForLoopGenerator? =
            ForInDefinitelySafeSimpleProgressionLoopGenerator(
                    codegen, forExpression,
                    startValue = startValue,
                    isStartInclusive = true,
                    endValue = StackValue.integerConstant(endIntValue, asmElementType),
                    isEndInclusive = true,
                    step = step
            )

    private fun createConstBoundedLongForLoopGenerator(
            codegen: ExpressionCodegen,
            forExpression: KtForExpression,
            startValue: StackValue,
            endLongValue: Long,
            step: Int
    ): ForLoopGenerator? =
            ForInDefinitelySafeSimpleProgressionLoopGenerator(
                    codegen, forExpression,
                    startValue = startValue,
                    isStartInclusive = true,
                    endValue = StackValue.constant(endLongValue, asmElementType),
                    isEndInclusive = true,
                    step = step
            )

    private fun isProhibitedCharConstEndValue(step: Int, endValue: Char) =
            endValue == if (step == 1) java.lang.Character.MAX_VALUE else java.lang.Character.MIN_VALUE

    private fun isProhibitedIntConstEndValue(step: Int, endValue: Int) =
            endValue == if (step == 1) Int.MAX_VALUE else Int.MIN_VALUE

    private fun isProhibitedLongConstEndValue(step: Int, endValue: Long) =
            endValue == if (step == 1) Long.MAX_VALUE else Long.MIN_VALUE

}