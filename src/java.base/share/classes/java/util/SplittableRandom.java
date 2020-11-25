/*
 * Copyright (c) 2013, 2020, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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
package java.util;

import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicLong;
import jdk.internal.util.random.RandomSupport;
import jdk.internal.util.random.RandomSupport.AbstractSplittableGenerator;
import jdk.internal.util.random.RandomSupport.RandomGeneratorProperty;

/**
 * A generator of uniform pseudorandom values (with period 2<sup>64</sup>)
 * applicable for use in (among other contexts) isolated parallel
 * computations that may generate subtasks. Class {@code SplittableRandom}
 * supports methods for producing pseudorandom numbers of type {@code int},
 * {@code long}, and {@code double} with similar usages as for class
 * {@link java.util.Random} but differs in the following ways:
 *
 * <ul>
 *
 * <li>Series of generated values pass the DieHarder suite testing
 * independence and uniformity properties of random number generators.
 * (Most recently validated with <a
 * href="http://www.phy.duke.edu/~rgb/General/dieharder.php"> version
 * 3.31.1</a>.) These tests validate only the methods for certain
 * types and ranges, but similar properties are expected to hold, at
 * least approximately, for others as well. The <em>period</em>
 * (length of any series of generated values before it repeats) is at
 * least 2<sup>64</sup>. </li>
 *
 * <li> Method {@link #split} constructs and returns a new
 * SplittableRandom instance that shares no mutable state with the
 * current instance. However, with very high probability, the
 * values collectively generated by the two objects have the same
 * statistical properties as if the same quantity of values were
 * generated by a single thread using a single {@code
 * SplittableRandom} object.  </li>
 *
 * <li>Instances of SplittableRandom are <em>not</em> thread-safe.
 * They are designed to be split, not shared, across threads. For
 * example, a {@link java.util.concurrent.ForkJoinTask
 * fork/join-style} computation using random numbers might include a
 * construction of the form {@code new
 * Subtask(aSplittableRandom.split()).fork()}.
 *
 * <li>This class provides additional methods for generating random
 * streams, that employ the above techniques when used in {@code
 * stream.parallel()} mode.</li>
 *
 * </ul>
 *
 * <p>Instances of {@code SplittableRandom} are not cryptographically
 * secure.  Consider instead using {@link java.security.SecureRandom}
 * in security-sensitive applications. Additionally,
 * default-constructed instances do not use a cryptographically random
 * seed unless the {@linkplain System#getProperty system property}
 * {@code java.util.secureRandomSeed} is set to {@code true}.
 *
 * @author  Guy Steele
 * @author  Doug Lea
 * @since   1.8
 */
public final class SplittableRandom extends AbstractSplittableGenerator {

    /*
     * Implementation Overview.
     *
     * This algorithm was inspired by the "DotMix" algorithm by
     * Leiserson, Schardl, and Sukha "Deterministic Parallel
     * Random-Number Generation for Dynamic-Multithreading Platforms",
     * PPoPP 2012, as well as those in "Parallel random numbers: as
     * easy as 1, 2, 3" by Salmon, Morae, Dror, and Shaw, SC 2011.  It
     * differs mainly in simplifying and cheapening operations.
     *
     * The primary update step (method nextSeed()) is to add a
     * constant ("gamma") to the current (64 bit) seed, forming a
     * simple sequence.  The seed and the gamma values for any two
     * SplittableRandom instances are highly likely to be different.
     *
     * Methods nextLong, nextInt, and derivatives do not return the
     * sequence (seed) values, but instead a hash-like bit-mix of
     * their bits, producing more independently distributed sequences.
     * For nextLong, the mix64 function is based on David Stafford's
     * (http://zimbry.blogspot.com/2011/09/better-bit-mixing-improving-on.html)
     * "Mix13" variant of the "64-bit finalizer" function in Austin
     * Appleby's MurmurHash3 algorithm (see
     * http://code.google.com/p/smhasher/wiki/MurmurHash3). The mix32
     * function is based on Stafford's Mix04 mix function, but returns
     * the upper 32 bits cast as int.
     *
     * The split operation uses the current generator to form the seed
     * and gamma for another SplittableRandom.  To conservatively
     * avoid potential correlations between seed and value generation,
     * gamma selection (method mixGamma) uses different
     * (Murmurhash3's) mix constants.  To avoid potential weaknesses
     * in bit-mixing transformations, we restrict gammas to odd values
     * with at least 24 0-1 or 1-0 bit transitions.  Rather than
     * rejecting candidates with too few or too many bits set, method
     * mixGamma flips some bits (which has the effect of mapping at
     * most 4 to any given gamma value).  This reduces the effective
     * set of 64bit odd gamma values by about 2%, and serves as an
     * automated screening for sequence constant selection that is
     * left as an empirical decision in some other hashing and crypto
     * algorithms.
     *
     * The resulting generator thus transforms a sequence in which
     * (typically) many bits change on each step, with an inexpensive
     * mixer with good (but less than cryptographically secure)
     * avalanching.
     *
     * The default (no-argument) constructor, in essence, invokes
     * split() for a common "defaultGen" SplittableRandom.  Unlike
     * other cases, this split must be performed in a thread-safe
     * manner, so we use an AtomicLong to represent the seed rather
     * than use an explicit SplittableRandom. To bootstrap the
     * defaultGen, we start off using a seed based on current time
     * unless the java.util.secureRandomSeed property is set. This
     * serves as a slimmed-down (and insecure) variant of SecureRandom
     * that also avoids stalls that may occur when using /dev/random.
     *
     * It is a relatively simple matter to apply the basic design here
     * to use 128 bit seeds. However, emulating 128bit arithmetic and
     * carrying around twice the state add more overhead than appears
     * warranted for current usages.
     *
     * File organization: First the non-public methods that constitute
     * the main algorithm, then the main public methods, followed by
     * some custom spliterator classes needed for stream methods.
     */

    /*
     * Period of SplittableRandom is 2**64
     */
    static final BigInteger PERIOD = BigInteger.ONE.shiftLeft(64);

    /*
     * Number of bits used to maintain state of seed.
     */
    private static final int STATE_BITS = 64;

    /*
     * The equidistribution of the algorithm.
     */
    private static final int EQUIDISTRIBUTION = 1;

    /*
     * RandomGenerator properties.
     */
    static Map<RandomGeneratorProperty, Object> getProperties() {
        return Map.of(
                RandomGeneratorProperty.NAME, "SplittableRandom",
                RandomGeneratorProperty.GROUP, "Legacy",
                RandomGeneratorProperty.PERIOD, PERIOD,
                RandomGeneratorProperty.STATE_BITS, STATE_BITS,
                RandomGeneratorProperty.EQUIDISTRIBUTION, EQUIDISTRIBUTION,
                RandomGeneratorProperty.IS_STOCHASTIC, false,
                RandomGeneratorProperty.IS_HARDWARE, false
        );
    }

    /**
     * The golden ratio scaled to 64bits, used as the initial gamma
     * value for (unsplit) SplittableRandoms.
     */
    private static final long GOLDEN_GAMMA = 0x9e3779b97f4a7c15L;

    /**
     * The seed. Updated only via method nextSeed.
     */
    private long seed;

    /**
     * The step value.
     */
    private final long gamma;

    /**
     * Internal constructor used by all others except default constructor.
     */
    private SplittableRandom(long seed, long gamma) {
        this.seed = seed;
        this.gamma = gamma;
    }

    /**
     * Computes Stafford variant 13 of 64bit mix function.
     * http://zimbry.blogspot.com/2011/09/better-bit-mixing-improving-on.html
     */
    private static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }

    /**
     * Returns the 32 high bits of Stafford variant 4 mix64 function as int.
     * http://zimbry.blogspot.com/2011/09/better-bit-mixing-improving-on.html
     */
    private static int mix32(long z) {
        z = (z ^ (z >>> 33)) * 0x62a9d9ed799705f5L;
        return (int)(((z ^ (z >>> 28)) * 0xcb24d0a5c88c35b3L) >>> 32);
    }

    /**
     * Returns the gamma value to use for a new split instance.
     * Uses the 64bit mix function from MurmurHash3.
     * https://github.com/aappleby/smhasher/wiki/MurmurHash3
     */
    private static long mixGamma(long z) {
        z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL; // MurmurHash3 mix constants
        z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L;
        z = (z ^ (z >>> 33)) | 1L;                  // force to be odd
        int n = Long.bitCount(z ^ (z >>> 1));       // ensure enough transitions
        return (n < 24) ? z ^ 0xaaaaaaaaaaaaaaaaL : z;
    }

    /**
     * Adds gamma to seed.
     */
    private long nextSeed() {
        return seed += gamma;
    }

    /**
     * The seed generator for default constructors.
     */
    private static final AtomicLong defaultGen = new AtomicLong(RandomSupport.initialSeed());

    /* ---------------- public methods ---------------- */

    /**
     * Creates a new SplittableRandom instance using the specified
     * initial seed. SplittableRandom instances created with the same
     * seed in the same program generate identical sequences of values.
     *
     * @param seed the initial seed
     */
    public SplittableRandom(long seed) {
        this(seed, GOLDEN_GAMMA);
    }

    /**
     * Creates a new SplittableRandom instance that is likely to
     * generate sequences of values that are statistically independent
     * of those of any other instances in the current program; and
     * may, and typically does, vary across program invocations.
     */
    public SplittableRandom() { // emulate defaultGen.split()
        long s = defaultGen.getAndAdd(2 * GOLDEN_GAMMA);
        this.seed = mix64(s);
        this.gamma = mixGamma(s + GOLDEN_GAMMA);
    }

    /**
     * Constructs and returns a new SplittableRandom instance that
     * shares no mutable state with this instance. However, with very
     * high probability, the set of values collectively generated by
     * the two objects has the same statistical properties as if the
     * same quantity of values were generated by a single thread using
     * a single SplittableRandom object.  Either or both of the two
     * objects may be further split using the {@code split()} method,
     * and the same expected statistical properties apply to the
     * entire set of generators constructed by such recursive
     * splitting.
     *
     * @return the new SplittableRandom instance
     */
    public SplittableRandom split() {
        return new SplittableRandom(nextLong(), mixGamma(nextSeed()));
    }

    public SplittableRandom split(SplittableGenerator source) {
        return new SplittableRandom(source.nextLong(), mixGamma(source.nextLong()));
    }

    @Override
    public int nextInt() {
        return mix32(nextSeed());
    }

    @Override
    public long nextLong() {
        return mix64(nextSeed());
    }

}
