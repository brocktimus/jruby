/***** BEGIN LICENSE BLOCK *****
 * Version: EPL 1.0/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Eclipse Public
 * License Version 1.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of
 * the License at http://www.eclipse.org/legal/epl-v10.html
 *
 * Software distributed under the License is distributed on an "AS
 * IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * rights and limitations under the License.
 *
 * Copyright (C) 2001 Chad Fowler <chadfowler@chadfowler.com>
 * Copyright (C) 2001 Alan Moore <alan_moore@gmx.net>
 * Copyright (C) 2001 Ed Sinjiashvili <slorcim@users.sourceforge.net>
 * Copyright (C) 2001-2004 Jan Arne Petersen <jpetersen@uni-bonn.de>
 * Copyright (C) 2002 Benoit Cerrina <b.cerrina@wanadoo.fr>
 * Copyright (C) 2002-2006 Thomas E Enebo <enebo@acm.org>
 * Copyright (C) 2002-2004 Anders Bengtsson <ndrsbngtssn@yahoo.se>
 * Copyright (C) 2004 Stefan Matthias Aust <sma@3plus4.de>
 * Copyright (C) 2005 Charles O Nutter <headius@headius.com>
 * Copyright (C) 2006 Miguel Covarrubias <mlcovarrubias@gmail.com>
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either of the GNU General Public License Version 2 or later (the "GPL"), or
 * the GNU Lesser General Public License Version 2.1 or later (the "LGPL"), in
 * which case the provisions of the GPL or the LGPL are applicable instead of
 * those above. If you wish to allow use of your version of this file only under
 * the terms of either the GPL or the LGPL, and not to allow others to use your
 * version of this file under the terms of the EPL, indicate your decision by
 * deleting the provisions above and replace them with the notice and other
 * provisions required by the GPL or the LGPL. If you do not delete the
 * provisions above, a recipient may use your version of this file under the
 * terms of any one of the EPL, the GPL or the LGPL.
 ***** END LICENSE BLOCK *****/
package org.jruby;

import java.io.IOException;
import java.util.List;

import org.jcodings.Encoding;
import org.jruby.anno.JRubyClass;
import org.jruby.anno.JRubyMethod;
import org.jruby.exceptions.JumpException;
import org.jruby.exceptions.RaiseException;
import org.jruby.runtime.CallSite;
import org.jruby.runtime.Helpers;
import org.jruby.runtime.Block;
import org.jruby.runtime.BlockCallback;
import org.jruby.runtime.CallBlock;
import org.jruby.runtime.ClassIndex;
import org.jruby.runtime.ObjectAllocator;
import org.jruby.runtime.ObjectMarshal;
import org.jruby.runtime.Signature;
import org.jruby.runtime.ThreadContext;

import static org.jruby.RubyEnumerator.enumeratorizeWithSize;
import static org.jruby.runtime.Visibility.*;

import org.jruby.runtime.builtin.IRubyObject;
import org.jruby.runtime.builtin.Variable;
import org.jruby.runtime.callsite.RespondToCallSite;
import org.jruby.runtime.component.VariableEntry;
import org.jruby.runtime.marshal.MarshalStream;
import org.jruby.runtime.marshal.UnmarshalStream;
import org.jruby.util.ByteList;
import org.jruby.util.TypeConverter;

import static org.jruby.runtime.Helpers.invokedynamic;
import static org.jruby.RubyEnumerator.SizeFn;
import static org.jruby.RubyNumeric.intervalStepSize;

import org.jruby.runtime.invokedynamic.MethodNames;

/**
 * @author jpetersen
 */
@JRubyClass(name = "Range", include = "Enumerable")
public class RubyRange extends RubyObject {

    private IRubyObject begin;
    private IRubyObject end;
    private boolean isExclusive;
    private boolean isInited = false;

    public static RubyClass createRangeClass(Ruby runtime) {
        RubyClass result = runtime.defineClass("Range", runtime.getObject(), RANGE_ALLOCATOR);
        runtime.setRange(result);

        result.setClassIndex(ClassIndex.RANGE);
        result.setReifiedClass(RubyRange.class);

        result.kindOf = new RubyModule.JavaClassKindOf(RubyRange.class);

        result.setMarshal(RANGE_MARSHAL);
        result.includeModule(runtime.getEnumerable());

        result.defineAnnotatedMethods(RubyRange.class);
        return result;
    }

    private static final ObjectAllocator RANGE_ALLOCATOR = new ObjectAllocator() {
        @Override
        public IRubyObject allocate(Ruby runtime, RubyClass klass) {
            return new RubyRange(runtime, klass);
        }
    };

    private RubyRange(Ruby runtime, RubyClass klass) {
        super(runtime, klass);
        begin = end = runtime.getNil();
    }

    public static RubyRange newRange(ThreadContext context, IRubyObject begin, IRubyObject end, boolean isExclusive) {
        RubyRange range = new RubyRange(context.runtime, context.runtime.getRange());
        range.init(context, begin, end, isExclusive);
        return range;
    }

    @Override
    public void copySpecialInstanceVariables(IRubyObject clone) {
        RubyRange range = (RubyRange) clone;
        range.begin = begin;
        range.end = end;
        range.isExclusive = isExclusive;
    }

    final boolean checkBegin(long length) {
        long beg = RubyNumeric.num2long(this.begin);
        if (beg < 0) {
            beg += length;
            if (beg < 0) {
                return false;
            }
        } else if (length < beg) {
            return false;
        }
        return true;
    }

    final long[] begLen(long len, int err) {
        long beg = RubyNumeric.num2long(this.begin);
        long end = RubyNumeric.num2long(this.end);

        if (beg < 0) {
            beg += len;
            if (beg < 0) {
                if (err != 0) {
                    throw getRuntime().newRangeError(beg + ".." + (isExclusive ? "." : "") + end + " out of range");
                }
                return null;
            }
        }

        if (err == 0 || err == 2) {
            if (beg > len) {
                if (err != 0) {
                    throw getRuntime().newRangeError(beg + ".." + (isExclusive ? "." : "") + end + " out of range");
                }
                return null;
            }
            if (end > len) {
                end = len;
            }
        }

        if (end < 0) {
            end += len;
        }
        if (!isExclusive) {
            end++;
        }
        len = end - beg;
        if (len < 0) {
            len = 0;
        }

        return new long[]{beg, len};
    }

    final long begLen0(long len) {
        long beg = RubyNumeric.num2long(this.begin);

        if (beg < 0) {
            beg += len;
            if (beg < 0) {
                throw getRuntime().newRangeError(beg + ".." + (isExclusive ? "." : "") + end + " out of range");
            }
        }

        return beg;
    }

    final long begLen1(long len, long beg) {
        long end = RubyNumeric.num2long(this.end);

        if (end < 0) {
            end += len;
        }
        if (!isExclusive) {
            end++;
        }
        len = end - beg;
        if (len < 0) {
            len = 0;
        }

        return len;
    }

    final int[] begLenInt(int len, int err) {
        int beg = RubyNumeric.num2int(this.begin);
        int end = RubyNumeric.num2int(this.end);

        if (beg < 0) {
            beg += len;
            if (beg < 0) {
                if (err != 0) {
                    throw getRuntime().newRangeError(beg + ".." + (isExclusive ? "." : "") + end + " out of range");
                }
                return null;
            }
        }

        if (err == 0 || err == 2) {
            if (beg > len) {
                if (err != 0) {
                    throw getRuntime().newRangeError(beg + ".." + (isExclusive ? "." : "") + end + " out of range");
                }
                return null;
            }
            if (end > len) {
                end = len;
            }
        }

        if (end < 0) {
            end += len;
        }
        if (!isExclusive) {
            end++;
        }
        len = end - beg;
        if (len < 0) {
            len = 0;
        }

        return new int[]{beg, len};
    }

    private void init(ThreadContext context, IRubyObject begin, IRubyObject end, boolean isExclusive) {
        if (!(begin instanceof RubyFixnum && end instanceof RubyFixnum)) {
            try {
                IRubyObject result = invokedynamic(context, begin, MethodNames.OP_CMP, end);
                if (result.isNil()) {
                    throw context.runtime.newArgumentError("bad value for range");
                }
            } catch (RaiseException re) {
                throw context.runtime.newArgumentError("bad value for range");
            }
        }

        this.begin = begin;
        this.end = end;
        this.isExclusive = isExclusive;
        this.isInited = true;
    }

    @JRubyMethod(required = 2, optional = 1, visibility = PRIVATE)
    public IRubyObject initialize(ThreadContext context, IRubyObject[] args, Block unusedBlock) {
        if (this.isInited) {
            throw context.runtime.newNameError("`initialize' called twice", "initialize");
        }
        init(context, args[0], args[1], args.length > 2 && args[2].isTrue());
        return context.nil;
    }

    @JRubyMethod(required = 1, visibility = PRIVATE)
    public IRubyObject initialize_copy(ThreadContext context, IRubyObject original) {
        if (this.isInited) {
            throw context.runtime.newNameError("`initialize' called twice", "initialize");
        }

        RubyRange other = (RubyRange) original;
        init(context, other.begin, other.end, other.isExclusive);
        return context.nil;
    }

    @JRubyMethod(name = "hash")
    public RubyFixnum hash(ThreadContext context) {
        long hash = isExclusive ? 1 : 0;
        long h = hash;

        long v = invokedynamic(context, begin, MethodNames.HASH).convertToInteger().getLongValue();
        hash ^= v << 1;
        v = invokedynamic(context, end, MethodNames.HASH).convertToInteger().getLongValue();
        hash ^= v << 9;
        hash ^= h << 24;
        return context.runtime.newFixnum(hash);
    }

    private IRubyObject inspectValue(final ThreadContext context, IRubyObject value) {
        return context.runtime.execRecursiveOuter(new Ruby.RecursiveFunction() {
            public IRubyObject call(IRubyObject obj, boolean recur) {
                if (recur) {
                    return RubyString.newString(context.runtime, isExclusive ? "(... ... ...)" : "(... .. ...)");
                } else {
                    return inspect(context, obj);
                }
            }
        }, value);
    }

    private static final byte[] DOTDOTDOT = new byte[]{'.', '.', '.'};

    @JRubyMethod(name = "inspect")
    public IRubyObject inspect(final ThreadContext context) {
        RubyString i1 = ((RubyString) inspectValue(context, begin)).strDup(context.runtime);
        RubyString i2 = (RubyString) inspectValue(context, end);
        i1.cat(DOTDOTDOT, 0, isExclusive ? 3 : 2);
        i1.append(i2);
        i1.infectBy(i2);
        i1.infectBy(this);
        return i1;
    }

    @JRubyMethod(name = "to_s")
    public IRubyObject to_s(final ThreadContext context) {
        RubyString i1 = begin.asString().strDup(context.runtime);
        RubyString i2 = end.asString();
        i1.cat(DOTDOTDOT, 0, isExclusive ? 3 : 2);
        i1.append(i2);
        i1.infectBy(i2);
        i1.infectBy(this);
        return i1;
    }

    @JRubyMethod(name = "exclude_end?")
    public RubyBoolean exclude_end_p() {
        return getRuntime().newBoolean(isExclusive);
    }

    @JRubyMethod(name = {"==", "eql?"}, required = 1)
    public IRubyObject op_equal(ThreadContext context, IRubyObject other) {
        if (this == other) {
            return context.runtime.getTrue();
        }
        if (!(other instanceof RubyRange)) {
            return context.runtime.getFalse();
        }
        RubyRange otherRange = (RubyRange) other;
        if (isExclusive != otherRange.isExclusive) {
            return context.runtime.getFalse();
        }
        if (!invokedynamic(context, this.begin, MethodNames.EQL, otherRange.begin).isTrue()) {
            return context.runtime.getFalse();
        }
        if (invokedynamic(context, this.end, MethodNames.EQL, otherRange.end).isTrue()) {
            return context.runtime.getTrue();
        }
        return context.runtime.getFalse();
    }

    private static abstract class RangeCallBack {

        abstract void call(ThreadContext context, IRubyObject arg);
    }

    private static final class StepBlockCallBack extends RangeCallBack implements BlockCallback {

        final Block block;
        IRubyObject iter;
        final IRubyObject step;

        StepBlockCallBack(Block block, IRubyObject iter, IRubyObject step) {
            this.block = block;
            this.iter = iter;
            this.step = step;
        }

        @Override
        public IRubyObject call(ThreadContext context, IRubyObject[] args, Block originalBlock) {
            call(context, args[0]);
            return context.nil;
        }

        @Override
        void call(ThreadContext context, IRubyObject arg) {
            if (iter instanceof RubyFixnum) {
                iter = RubyFixnum.newFixnum(context.runtime, ((RubyFixnum) iter).getLongValue() - 1);
            } else {
                iter = iter.callMethod(context, "-", RubyFixnum.one(context.runtime));
            }
            if (iter == RubyFixnum.zero(context.runtime)) {
                block.yield(context, arg);
                iter = step;
            }
        }
    }

    private static IRubyObject rangeLt(ThreadContext context, IRubyObject a, IRubyObject b) {
        IRubyObject result = invokedynamic(context, a, MethodNames.OP_CMP, b);
        if (result.isNil()) {
            return null;
        }
        return RubyComparable.cmpint(context, result, a, b) < 0 ? context.runtime.getTrue() : null;
    }

    private static IRubyObject rangeLe(ThreadContext context, IRubyObject a, IRubyObject b) {
        IRubyObject result = invokedynamic(context, a, MethodNames.OP_CMP, b);
        if (result.isNil()) {
            return null;
        }
        int c = RubyComparable.cmpint(context, result, a, b);
        if (c == 0) {
            return RubyFixnum.zero(context.runtime);
        }
        return c < 0 ? context.runtime.getTrue() : null;
    }

    private void rangeEach(ThreadContext context, RangeCallBack callback) {
        IRubyObject v = begin;
        if (isExclusive) {
            while (rangeLt(context, v, end) != null) {
                callback.call(context, v);
                v = v.callMethod(context, "succ");
            }
        } else {
            IRubyObject c;
            while ((c = rangeLe(context, v, end)) != null && c.isTrue()) {
                callback.call(context, v);
                if (c == RubyFixnum.zero(context.runtime)) {
                    break;
                }
                v = v.callMethod(context, "succ");
            }
        }
    }

    @JRubyMethod
    public IRubyObject to_a(ThreadContext context, final Block block) {
        final Ruby runtime = context.runtime;

        if (begin instanceof RubyFixnum && end instanceof RubyFixnum) {
            long lim = ((RubyFixnum) end).getLongValue();
            if (!isExclusive) {
                lim++;
            }

            long base = ((RubyFixnum) begin).getLongValue();
            long size = lim - base;
            if (size > Integer.MAX_VALUE) {
                throw runtime.newRangeError("Range size too large for to_a");
            }
            if (size < 0) {
                return RubyArray.newEmptyArray(runtime);
            }
            IRubyObject[] array = new IRubyObject[(int) size];
            for (int i = 0; i < size; i++) {
                array[i] = RubyFixnum.newFixnum(runtime, base + i);
            }
            return RubyArray.newArrayMayCopy(runtime, array);
        } else {
            return RubyEnumerable.to_a(context, this);
        }
    }

    @Deprecated
    public IRubyObject each19(ThreadContext context, final Block block) {
        return each(context, block);
    }

    @JRubyMethod(name = "each")
    public IRubyObject each(final ThreadContext context, final Block block) {
        if (!block.isGiven()) {
            return enumeratorizeWithSize(context, this, "each", enumSizeFn(context));
        }
        final Ruby runtime = context.runtime;
        if (begin instanceof RubyTime) {
            throw runtime.newTypeError("can't iterate from Time");
        } else if (begin instanceof RubyFixnum && end instanceof RubyFixnum) {
            fixnumEach(context, runtime, block);
        } else if (begin instanceof RubySymbol) {
            begin.asString().uptoCommon(context, end.asString(), isExclusive, block, true);
        } else {
            IRubyObject tmp = begin.checkStringType();
            if (!tmp.isNil()) {
                ((RubyString) tmp).uptoCommon(context, end, isExclusive, block);
            } else {
                if (!begin.respondsTo("succ")) {
                    throw runtime.newTypeError("can't iterate from "
                            + begin.getMetaClass().getName());
                }
                rangeEach(context, new RangeCallBack() {
                    @Override
                    void call(ThreadContext context, IRubyObject arg) {
                        block.yield(context, arg);
                    }
                });
            }
        }
        return this;
    }

    private void fixnumEach(ThreadContext context, Ruby runtime, Block block) {
        // We must avoid integer overflows.
        long to = ((RubyFixnum) end).getLongValue();
        if (isExclusive) {
            if (to == Long.MIN_VALUE) {
                return;
            }
            to--;
        }
        long from = ((RubyFixnum) begin).getLongValue();
        if (block.getSignature() == Signature.NO_ARGUMENTS) {
            IRubyObject nil = runtime.getNil();
            long i;
            for (i = from; i < to; i++) {
                block.yield(context, nil);
            }
            if (i <= to) {
                block.yield(context, nil);
            }
        } else {
            long i;
            for (i = from; i < to; i++) {
                block.yield(context, RubyFixnum.newFixnum(runtime, i));
            }
            if (i <= to) {
                block.yield(context, RubyFixnum.newFixnum(runtime, i));
            }
        }
    }

    @Deprecated
    public IRubyObject step19(ThreadContext context, IRubyObject step, Block block) {
        return step(context, step, block);
    }

    @Deprecated
    public IRubyObject step19(ThreadContext context, Block block) {
        return step(context, block);
    }

    @JRubyMethod(name = "step")
    public IRubyObject step(final ThreadContext context, final Block block) {
        return block.isGiven() ? stepCommon(context, RubyFixnum.one(context.runtime), block) : enumeratorizeWithSize(context, this, "step", stepSizeFn(context));
    }

    @JRubyMethod(name = "step")
    public IRubyObject step(final ThreadContext context, IRubyObject step, final Block block) {
        Ruby runtime = context.runtime;
        if (!block.isGiven()) {
            return enumeratorizeWithSize(context, this, "step", new IRubyObject[]{step}, stepSizeFn(context));
        }

        if (!(step instanceof RubyNumeric)) {
            step = step.convertToInteger("to_int");
        }
        IRubyObject zero = RubyFixnum.zero(runtime);
        if (step.callMethod(context, "<", zero).isTrue()) {
            throw runtime.newArgumentError("step can't be negative");
        }
        if (!step.callMethod(context, ">", zero).isTrue()) {
            throw runtime.newArgumentError("step can't be 0");
        }
        return stepCommon(context, step, block);
    }

    private IRubyObject stepCommon(ThreadContext context, IRubyObject step, Block block) {
        Ruby runtime = context.runtime;
        if (begin instanceof RubyFixnum && end instanceof RubyFixnum && step instanceof RubyFixnum) {
            fixnumStep(context, runtime, ((RubyFixnum) step).getLongValue(), block);
        } else if (begin instanceof RubyFloat || end instanceof RubyFloat || step instanceof RubyFloat) {
            RubyNumeric.floatStep(context, runtime, begin, end, step, isExclusive, block);
        } else if (begin instanceof RubyNumeric
                || !TypeConverter.checkIntegerType(runtime, begin, "to_int").isNil()
                || !TypeConverter.checkIntegerType(runtime, end, "to_int").isNil()) {
            numericStep(context, runtime, step, block);
        } else {
            IRubyObject tmp = begin.checkStringType();
            if (!tmp.isNil()) {
                StepBlockCallBack callback = new StepBlockCallBack(block, RubyFixnum.one(runtime), step);
                Block blockCallback = CallBlock.newCallClosure(this, runtime.getRange(), Signature.ONE_ARGUMENT, callback, context);
                ((RubyString) tmp).uptoCommon(context, end, isExclusive, blockCallback);
            } else {
                if (!begin.respondsTo("succ")) {
                    throw runtime.newTypeError("can't iterate from " + begin.getMetaClass().getName());
                }
                // range_each_func(range, step_i, b, e, args);
                rangeEach(context, new StepBlockCallBack(block, RubyFixnum.one(runtime), step));
            }
        }
        return this;
    }

    private void fixnumStep(ThreadContext context, Ruby runtime, long step, Block block) {
        // We must avoid integer overflows.
        // Any method calling this method must ensure that "step" is greater than 0.
        long to = ((RubyFixnum) end).getLongValue();
        if (isExclusive) {
            if (to == Long.MIN_VALUE) {
                return;
            }
            to--;
        }
        long tov = Long.MAX_VALUE - step;
        if (to < tov) {
            tov = to;
        }
        long i;
        for (i = ((RubyFixnum) begin).getLongValue(); i <= tov; i += step) {
            block.yield(context, RubyFixnum.newFixnum(runtime, i));
        }
        if (i <= to) {
            block.yield(context, RubyFixnum.newFixnum(runtime, i));
        }
    }

    private void numericStep(ThreadContext context, Ruby runtime, IRubyObject step, Block block) {
        final String method = isExclusive ? "<" : "<=";
        IRubyObject beg = begin;
        long i = 0;
        while (beg.callMethod(context, method, end).isTrue()) {
            block.yield(context, beg);
            i++;
            beg = begin.callMethod(context, "+", RubyFixnum.newFixnum(runtime, i).callMethod(context, "*", step));
        }
    }

    private SizeFn enumSizeFn(final ThreadContext context) {
        final RubyRange self = this;
        return new SizeFn() {
            @Override
            public IRubyObject size(IRubyObject[] args) {
                return self.size(context);
            }
        };
    }

    private SizeFn stepSizeFn(final ThreadContext context) {
        final RubyRange self = this;
        return new SizeFn() {
            @Override
            public IRubyObject size(IRubyObject[] args) {
                Ruby runtime = context.runtime;
                IRubyObject begin = self.begin;
                IRubyObject end = self.end;
                IRubyObject step;

                if (args != null && args.length > 0) {
                    step = args[0];
                    if (!(step instanceof RubyNumeric)) {
                        step.convertToInteger();
                    }
                } else {
                    step = RubyFixnum.one(runtime);
                }

                if (step.callMethod(context, "<", RubyFixnum.zero(runtime)).isTrue()) {
                    throw runtime.newArgumentError("step can't be negative");
                } else if (!step.callMethod(context, ">", RubyFixnum.zero(runtime)).isTrue()) {
                    throw runtime.newArgumentError("step can't be 0");
                }

                if (begin instanceof RubyNumeric && end instanceof RubyNumeric) {
                    return intervalStepSize(context, begin, end, step, self.isExclusive);
                }

                return runtime.getNil();
            }
        };
    }

    @Deprecated
    public IRubyObject include_p19(ThreadContext context, IRubyObject obj) {
        return include_p(context, obj);
    }

    // framed for invokeSuper
    @JRubyMethod(name = {"include?", "member?"}, frame = true)
    public IRubyObject include_p(ThreadContext context, final IRubyObject obj) {
        final Ruby runtime = context.runtime;
        if ( begin instanceof RubyNumeric || end instanceof RubyNumeric
            || ! TypeConverter.convertToTypeWithCheck(begin, runtime.getInteger(), "to_int").isNil()
            || ! TypeConverter.convertToTypeWithCheck(end, runtime.getInteger(), "to_int").isNil() ) {
            return cover_p(context, obj);
        }
        if ( begin instanceof RubyString && end instanceof RubyString
            && ((RubyString) begin).getByteList().getRealSize() == 1
            && ((RubyString) end).getByteList().getRealSize() == 1 ) {
            if (obj.isNil()) return runtime.getFalse();
            if (obj instanceof RubyString) {
                ByteList objBytes = ((RubyString) obj).getByteList();
                if (objBytes.getRealSize() != 1) return runtime.getFalse();
                int v = objBytes.getUnsafeBytes()[objBytes.getBegin()] & 0xff;
                ByteList begBytes = ((RubyString) begin).getByteList();
                int b = begBytes.getUnsafeBytes()[begBytes.getBegin()] & 0xff;
                ByteList endBytes = ((RubyString) end).getByteList();
                int e = endBytes.getUnsafeBytes()[endBytes.getBegin()] & 0xff;
                if (Encoding.isAscii(v) && Encoding.isAscii(b) && Encoding.isAscii(e)) {
                    if ((b <= v && v < e) || (!isExclusive && v == e)) {
                        return runtime.getTrue();
                    }
                    return runtime.getFalse();
                }
            }
        }
        return Helpers.invokeSuper(context, this, obj, Block.NULL_BLOCK);
    }

    @JRubyMethod(name = "===")
    public IRubyObject eqq_p(ThreadContext context, IRubyObject obj) {
        return callMethod(context, "include?", obj);
    }

    @JRubyMethod(name = "cover?")
    public RubyBoolean cover_p(ThreadContext context, IRubyObject obj) {
        if (rangeLe(context, begin, obj) == null) {
            return context.runtime.getFalse(); // obj < start...end
        }
        return context.runtime.newBoolean(isExclusive
                ? // begin <= obj < end || begin <= obj <= end
                rangeLt(context, obj, end) != null : rangeLe(context, obj, end) != null);
    }

    @JRubyMethod(frame = true)
    public IRubyObject min(ThreadContext context, Block block) {
        if (block.isGiven()) {
            return Helpers.invokeSuper(context, this, block);
        }

        int cmp = RubyComparable.cmpint(context, invokedynamic(context, begin, MethodNames.OP_CMP, end), begin, end);
        if (cmp > 0 || (cmp == 0 && isExclusive)) {
            return context.nil;
        }

        return begin;
    }

    @JRubyMethod(frame = true)
    public IRubyObject max(ThreadContext context, Block block) {
        boolean isNumeric = end instanceof RubyNumeric;

        if (block.isGiven() || (isExclusive && !isNumeric)) {
            return Helpers.invokeSuper(context, this, block);
        }

        int cmp = RubyComparable.cmpint(context, invokedynamic(context, begin, MethodNames.OP_CMP, end), begin, end);
        if (cmp > 0) {
            return context.nil;
        }
        if (isExclusive) {
            if (!(end instanceof RubyInteger)) {
                throw context.runtime.newTypeError("cannot exclude non Integer end value");
            }

            if (cmp == 0) {
                return context.nil;
            }

            if (!(begin instanceof RubyInteger)) {
                throw context.runtime.newTypeError("cannot exclude end value with non Integer begin value");
            }
            if (end instanceof RubyFixnum) {
                return RubyFixnum.newFixnum(context.runtime, ((RubyFixnum) end).getLongValue() - 1);
            }

            return end.callMethod(context, "-", RubyFixnum.one(context.runtime));
        }

        return end;
    }

    @JRubyMethod(frame = true)
    public IRubyObject min(ThreadContext context, IRubyObject arg, Block block) {
        return Helpers.invokeSuper(context, this, arg, block);
    }

    @JRubyMethod(frame = true)
    public IRubyObject max(ThreadContext context, IRubyObject arg, Block block) {
        return Helpers.invokeSuper(context, this, arg, block);
    }

    @JRubyMethod
    public IRubyObject first(ThreadContext context) {
        return begin;
    }

    @JRubyMethod
    public IRubyObject begin(ThreadContext context) {
        return begin;
    }

    @JRubyMethod
    public IRubyObject first(ThreadContext context, IRubyObject arg) {
        final Ruby runtime = context.runtime;
        final int num = RubyNumeric.num2int(arg);
        if (num < 0) {
            throw context.runtime.newArgumentError("negative array size (or size too big)");
        }
        // TODO (CON): this could be packed if we know there are at least num elements in range
        final RubyArray result = runtime.newArray(num);
        try {
            RubyEnumerable.callEach(runtime, context, this, Signature.ONE_ARGUMENT, new BlockCallback() {
                int n = num;

                @Override
                public IRubyObject call(ThreadContext ctx, IRubyObject[] largs, Block blk) {
                    if (n-- <= 0) {
                        throw JumpException.SPECIAL_JUMP;
                    }
                    result.append(largs[0]);
                    return runtime.getNil();
                }
            });
        } catch (JumpException.SpecialJump sj) {
        }
        return result;
    }

    @JRubyMethod
    public IRubyObject last(ThreadContext context) {
        return end;
    }

    @JRubyMethod
    public IRubyObject end(ThreadContext context) {
        return end;
    }

    @JRubyMethod
    public IRubyObject last(ThreadContext context, IRubyObject arg) {
        return ((RubyArray) RubyKernel.new_array(context, this, this)).last(arg);
    }

    @JRubyMethod
    public IRubyObject size(ThreadContext context) {
        if (begin instanceof RubyNumeric && end instanceof RubyNumeric) {
            return RubyNumeric.intervalStepSize(context, begin, end, RubyFixnum.one(context.runtime), isExclusive);
        }
        return context.nil;
    }

    public final boolean isExcludeEnd() {
        return isExclusive;
    }

    private static final ObjectMarshal RANGE_MARSHAL = new ObjectMarshal() {
        @Override
        public void marshalTo(Ruby runtime, Object obj, RubyClass type,
                MarshalStream marshalStream) throws IOException {
            RubyRange range = (RubyRange) obj;

            marshalStream.registerLinkTarget(range);
            List<Variable<Object>> attrs = range.getVariableList();

            attrs.add(new VariableEntry<Object>("begin", range.begin));
            attrs.add(new VariableEntry<Object>("end", range.end));
            attrs.add(new VariableEntry<Object>("excl", range.isExclusive ? runtime.getTrue() : runtime.getFalse()));

            marshalStream.dumpVariables(attrs);
        }

        @Override
        public Object unmarshalFrom(Ruby runtime, RubyClass type,
                UnmarshalStream unmarshalStream) throws IOException {
            RubyRange range = (RubyRange) type.allocate();

            unmarshalStream.registerLinkTarget(range);

            // FIXME: Maybe we can just gank these off the line directly?
            unmarshalStream.defaultVariablesUnmarshal(range);

            range.begin = (IRubyObject) range.removeInternalVariable("begin");
            range.end = (IRubyObject) range.removeInternalVariable("end");
            range.isExclusive = ((IRubyObject) range.removeInternalVariable("excl")).isTrue();

            return range;
        }
    };

    /**
     * Given a range-line object that response to "begin", "end", construct a proper range
     * by calling those methods and "exclude_end?" with the given call sites.
     *
     * @param context current context
     * @param rangeLike range-like object
     * @param beginSite "begin" call site
     * @param endSite "end" call site
     * @param excludeEndSite "exclude_end?" call site
     * @return a proper Range based on the results of calling those methods
     */
    public static RubyRange rangeFromRangeLike(ThreadContext context, IRubyObject rangeLike, CallSite beginSite, CallSite endSite, CallSite excludeEndSite) {
        IRubyObject begin = beginSite.call(context, rangeLike, rangeLike);
        IRubyObject end   = endSite.call(context, rangeLike, rangeLike);
        IRubyObject excl  = excludeEndSite.call(context, rangeLike, rangeLike);
        return newRange(context, begin, end, excl.isTrue());
    }

    /**
     * Return true if the given object responds to "begin" and "end" methods.
     *
     * @param context current context
     * @param obj possibly range-like object
     * @param respond_to_begin respond_to? site for begin
     * @param respond_to_end respond_to? site for end
     * @return
     */
    public static boolean isRangeLike(ThreadContext context, IRubyObject obj, RespondToCallSite respond_to_begin, RespondToCallSite respond_to_end) {
        return respond_to_begin.respondsTo(context, obj, obj) &&
                respond_to_end.respondsTo(context, obj, obj);
    }
}
