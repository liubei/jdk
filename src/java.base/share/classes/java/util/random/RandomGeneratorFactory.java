/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
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

package java.util.random;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.Objects;
import java.util.function.Function;
import java.util.Map;
import java.util.random.RandomGenerator.ArbitrarilyJumpableGenerator;
import java.util.random.RandomGenerator.JumpableGenerator;
import java.util.random.RandomGenerator.LeapableGenerator;
import java.util.random.RandomGenerator.SplittableGenerator;
import java.util.random.RandomGenerator.StreamableGenerator;
import java.util.ServiceLoader;
import java.util.ServiceLoader.Provider;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import jdk.internal.util.random.RandomSupport.RandomGeneratorProperties;

/**
 * This is a factory class for generating multiple random number generators
 * of a specific algorithm. {@link RandomGeneratorFactory} also provides
 * methods for selecting random number generator algorithms.
 *
 * A specific {@link RandomGeneratorFactory} can be located by using the
 * {@link RandomGenerator#factoryOf(String)} method, where the argument string
 * is the name of the algorithm required. The method
 * {@link RandomGenerator#all()} produces a {@link Stream} of all available
 * {@link RandomGenerator RandomGenerators} that can be searched to locate a
 * {@link RandomGeneratorFactory} suitable to the task.
 *
 * There are three methods for constructing a RandomGenerator instance,
 * depending on the type of initial seed required.
 * {@link RandomGeneratorFactory#create(long)} is used for long
 * seed construction,
 * {@link RandomGeneratorFactory#create(byte[])} is used for byte[]
 * seed construction, and
 * {@link RandomGeneratorFactory#create()} is used for random seed
 * construction. Example;
 *
 * <pre>{@code
 *    RandomGeneratorFactory<RandomGenerator> factory = RandomGenerator.factoryOf("Random");
 *
 *     for (int i = 0; i < 10; i++) {
 *         new Thread(() -> {
 *             RandomGenerator random = factory.create(100L);
 *             System.out.println(random.nextDouble());
 *         }).start();
 *     }
 * }</pre>
 *
 * RandomGeneratorFactory also provides methods describing the attributes (or properties)
 * of a generator and can be used to select random number generator algorithms.
 * These methods are typically used in
 * conjunction with {@link RandomGenerator#all()}. In this example, the code locates the
 * {@link RandomGeneratorFactory} that produces {@link RandomGenerator RandomGenerators}
 * with the highest number of state bits.
 *
 * <pre>{@code
 *     RandomGeneratorFactory<RandomGenerator> best = RandomGenerator.all()
 *         .sorted(Comparator.comparingInt(RandomGenerator::stateBits).reversed())
 *         .findFirst()
 *         .orElse(RandomGenerator.factoryOf("Random"));
 *     System.out.println(best.name() + " in " + best.group() + " was selected");
 *
 *     RandomGenerator rng = best.create();
 *     System.out.println(rng.nextLong());
 * }</pre>
 *
 * @since   17
 *
 * @see java.util.random
 *
 */
public final class RandomGeneratorFactory<T extends RandomGenerator> {
    /**
     * Map of provider classes.
     */
    private static volatile Map<String, Provider<? extends RandomGenerator>> factoryMap;

    /**
     * Instance provider class of random number algorithm.
     */
    private final Provider<? extends RandomGenerator> provider;

    /**
     * Provider RandomGeneratorProperties annotation.
     */
    private volatile RandomGeneratorProperties properties;

    /**
     * Default provider constructor.
     */
    private volatile Constructor<T> ctor;

    /**
     * Provider constructor with long seed.
     */
    private Constructor<T> ctorLong;

    /**
     * Provider constructor with byte[] seed.
     */
    private Constructor<T> ctorBytes;


    private static class FactoryMapHolder {
        static final Map<String, Provider<? extends RandomGenerator>> FACTORY_MAP = createFactoryMap();

        /**
         * Returns the factory map, lazily constructing map on first use.
         *
         * @return Map of RandomGeneratorFactory classes.
         */
        private static Map<String, Provider<? extends RandomGenerator>> createFactoryMap() {
            return ServiceLoader
                .load(RandomGenerator.class)
                .stream()
                .filter(p -> !p.type().isInterface())
                .collect(Collectors.toMap(p -> p.type().getSimpleName(), Function.identity()));
        }
    }

    /**
     * Private constructor.
     *
     * @param provider  Provider class to wrap.
     */
    private RandomGeneratorFactory(Provider<? extends RandomGenerator> provider) {
        this.provider = provider;
    }

    /**
     * Returns the factory map, lazily constructing map on first call.
     *
     * @return Map of RandomGeneratorFactory classes.
     */
    private static Map<String, Provider<? extends RandomGenerator>> getFactoryMap() {
        return FactoryMapHolder.FACTORY_MAP;
    }

    /**
     * Return the annotation for the specified provider.
     *
     * @return RandomGeneratorProperties annotation for the specified provider.
     */
     private RandomGeneratorProperties getProperties() {
        if (properties == null) {
            synchronized (provider) {
                if (properties == null) {
                    properties = provider.type().getDeclaredAnnotation(RandomGeneratorProperties.class);
                    Objects.requireNonNull(properties, provider.type() + " missing annotation");
                }
            }
        }

        return properties;
    }

    /**
     * Return true if the provider is a subclass of the category.
     *
     * @param category Interface category, sub-interface of {@link RandomGenerator}.
     *
     * @return true if the provider is a subclass of the category.
     */
    private boolean isSubclass(Class<? extends RandomGenerator> category) {
        return isSubclass(category, provider);
    }

    /**
     * Return true if the provider is a subclass of the category.
     *
     * @param category Interface category, sub-interface of {@link RandomGenerator}.
     * @param provider Provider that is being filtered.
     *
     * @return true if the provider is a subclass of the category.
     */
    private static boolean isSubclass(Class<? extends RandomGenerator> category,
                                      Provider<? extends RandomGenerator> provider) {
        return provider != null && category.isAssignableFrom(provider.type());
    }

    /**
     * Returns the provider matching name and category.
     *
     * @param name      Name of RandomGenerator
     * @param category  Interface category, sub-interface of {@link RandomGenerator}.
     *
     * @return A provider matching name and category.
     *
     * @throws IllegalArgumentException if provider is not a subclass of category.
     */
    private static Provider<? extends RandomGenerator> findProvider(String name,
                                                                    Class<? extends RandomGenerator> category)
            throws IllegalArgumentException {
        Map<String, Provider<? extends RandomGenerator>> fm = getFactoryMap();
        Provider<? extends RandomGenerator> provider = fm.get(name);
        if (!isSubclass(category, provider)) {
            throw new IllegalArgumentException(name + " is an unknown random number generator");
        }
        return provider;
    }

    /**
     * Returns a stream of matching Providers.
     *
     * @param category  Sub-interface of {@link RandomGenerator} to type check
     * @param <T>       Sub-interface of {@link RandomGenerator} to produce
     *
     * @return Stream of matching Providers.
     */
    static <T extends RandomGenerator> Stream<RandomGeneratorFactory<T>> all(Class<T> category) {
        Map<String, Provider<? extends RandomGenerator>> fm = getFactoryMap();
        return fm.values()
                 .stream()
                 .filter(p -> isSubclass(category, p))
                 .map(RandomGeneratorFactory::new);
    }

    /**
     * Returns a {@link RandomGenerator} that utilizes the {@code name} algorithm.
     *
     * @param name      Name of random number algorithm to use
     * @param category  Sub-interface of {@link RandomGenerator} to type check
     * @param <T>       Sub-interface of {@link RandomGenerator} to produce
     *
     * @return An instance of {@link RandomGenerator}
     *
     * @throws IllegalArgumentException when either the name or category is null
     */
    static <T extends RandomGenerator> T of(String name, Class<T> category)
            throws IllegalArgumentException {
        @SuppressWarnings("unchecked")
        T uncheckedRandomGenerator = (T)findProvider(name, category).get();
        return uncheckedRandomGenerator;
    }

    /**
     * Returns a {@link RandomGeneratorFactory} that will produce instances
     * of {@link RandomGenerator} that utilizes the {@code name} algorithm.
     *
     * @param name  Name of random number algorithm to use
     * @param category Sub-interface of {@link RandomGenerator} to type check
     * @param <T> Sub-interface of {@link RandomGenerator} to produce
     *
     * @return Factory of {@link RandomGenerator}
     *
     * @throws IllegalArgumentException when either the name or category is null
     */
    static <T extends RandomGenerator> RandomGeneratorFactory<T> factoryOf(String name, Class<T> category)
            throws IllegalArgumentException {
        Provider<? extends RandomGenerator> uncheckedProvider = findProvider(name, category);
        return new RandomGeneratorFactory<>(uncheckedProvider);
    }

    /**
     * Fetch the required constructors for class of random number algorithm.
     *
     * @param randomGeneratorClass class of random number algorithm (provider)
     */
    private void getConstructors(Class<? extends RandomGenerator> randomGeneratorClass) {
        if (ctor == null) {
            synchronized (provider) {
                if (ctor == null) {
                    PrivilegedExceptionAction<Constructor<?>[]> ctorAction = randomGeneratorClass::getConstructors;
                    try {
                        Constructor<?>[] ctors = AccessController.doPrivileged(ctorAction);

                        Constructor<T> tmpCtor = null;
                        Constructor<T> tmpCtorLong = null;
                        Constructor<T> tmpCtorBytes = null;


                        for (Constructor<?> ctorGeneric : ctors) {
                            @SuppressWarnings("unchecked")
                            Constructor<T> ctorSpecific = (Constructor<T>) ctorGeneric;
                            final Class<?>[] parameterTypes = ctorSpecific.getParameterTypes();

                            if (parameterTypes.length == 0) {
                                tmpCtor = ctorSpecific;
                            } else if (parameterTypes.length == 1) {
                                Class<?> argType = parameterTypes[0];

                                if (argType == long.class) {
                                    tmpCtorLong = ctorSpecific;
                                } else if (argType == byte[].class) {
                                    tmpCtorBytes = ctorSpecific;
                                }
                            }
                        }

                        if (tmpCtor == null) {
                            throw new IllegalStateException("Random algorithm " + name() + " is missing a default constructor");
                        }

                        // Store specialized constructors first, guarded by ctor
                        ctorBytes = tmpCtorBytes;
                        ctorLong = tmpCtorLong;
                        ctor = tmpCtor;
                    } catch (PrivilegedActionException ex) {
                        // Do nothing
                    }
                }
            }
        }
    }

    /**
     * Ensure all the required constructors are fetched.
     */
    private void ensureConstructors() {
        getConstructors(provider.type());
    }

    /**
     * Return the name of the algorithm used by the random number generator.
     *
     * @return Name of the algorithm.
     */
    public String name() {
        return provider.type().getSimpleName();
    }

    /**
     * Return the group name of the algorithm used by the random number generator.
     *
     * @return Group name of the algorithm.
     */
    public String group() {
        return getProperties().group();
    }

    /**
     * Returns number of bits used to maintain state of seed.
     *
     * @return number of bits used to maintain state of seed.
     */
    public int stateBits() {
        RandomGeneratorProperties properties = getProperties();
        int i = properties.i();
        int k = properties.k();

        return i == 0 && k == 0 ? Integer.MAX_VALUE : i + k;
    }

    /**
     * Returns the equidistribution of the algorithm.
     *
     * @return the equidistribution of the algorithm.
     */
    public int equidistribution() {
        return getProperties().equidistribution();
    }

    /**
     * Return the period of the algorithm used by the random number generator.
     *
     * @return BigInteger period.
     */
    public BigInteger period() {
        RandomGeneratorProperties properties = getProperties();
        int i = properties.i();
        int j = properties.j();
        int k = properties.k();

        if (i == 0 && j == 0 && k == 0) {
            return RandomGenerator.HUGE_PERIOD;
        } else {
            return BigInteger.ONE.shiftLeft(i).subtract(BigInteger.valueOf(j)).shiftLeft(k);
        }
    }

    /**
     * Return true if random generator is statistical.
     *
     * @return true if random generator is statistical.
     */
    public boolean isStatistical() {
        return !getProperties().isStochastic();
    }

    /**
     * Return true if random generator is stochastic.
     *
     * @return true if random generator is stochastic.
     */
    public boolean isStochastic() {
        return getProperties().isStochastic();
    }

    /**
     * Return true if random generator is generated by hardware.
     *
     * @return true if random generator is generated by hardware.
     */
    public boolean isHardware() {
        return getProperties().isHardware();
    }

    /**
     * Return true if random generator is arbitrarily jumpable.
     *
     * @return true if random generator is arbitrarily jumpable.
     */
    public boolean isArbitrarilyJumpable() {
        return isSubclass(ArbitrarilyJumpableGenerator.class);
    }

    /**
     * Return true if random generator is jumpable.
     *
     * @return true if random generator is jumpable.
     */
    public boolean isJumpable() {
        return isSubclass(JumpableGenerator.class);
    }

    /**
     * Return true if random generator is leapable.
     *
     * @return true if random generator is leapable.
     */
    public boolean isLeapable() {
        return isSubclass(LeapableGenerator.class);
    }

    /**
     * Return true if random generator is splittable.
     *
     * @return true if random generator is splittable.
     */
    public boolean isSplittable() {
        return isSubclass(SplittableGenerator.class);
    }

    /**
     * Return true if random generator is streamable.
     *
     * @return true if random generator is streamable.
     */
    public boolean isStreamable() {
        return isSubclass(StreamableGenerator.class);
    }

    /**
     * Create an instance of {@link RandomGenerator} based on algorithm chosen.
     *
     * @return new in instance of {@link RandomGenerator}.
     *
     */
    public T create() {
        try {
            ensureConstructors();
            return ctor.newInstance();
        } catch (Exception ex) {
            // Should never happen.
            throw new IllegalStateException("Random algorithm " + name() + " is missing a default constructor", ex);
        }
    }

    /**
     * Create an instance of {@link RandomGenerator} based on algorithm chosen
     * providing a starting long seed. If long seed is not supported by an
     * algorithm then the no argument form of create is used.
     *
     * @param seed long random seed value.
     *
     * @return new in instance of {@link RandomGenerator}.
     */
    public T create(long seed) {
        try {
            ensureConstructors();
            return ctorLong.newInstance(seed);
        } catch (Exception ex) {
            return create();
        }
    }

    /**
     * Create an instance of {@link RandomGenerator} based on algorithm chosen
     * providing a starting byte[] seed. If byte[] seed is not supported by an
     * algorithm then the no argument form of create is used.
     *
     * @param seed byte array random seed value.
     *
     * @return new in instance of {@link RandomGenerator}.
     *
     * @throws NullPointerException if seed is null.
     */
    public T create(byte[] seed) {
        try {
            ensureConstructors();
            return ctorBytes.newInstance((Object)seed);
        } catch (Exception ex) {
            return create();
        }
    }

}


