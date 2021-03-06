/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.graalvm.polyglot;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Represents the host access policy of a polyglot context. The host access policy specifies which
 * methods and fields are accessible to the guest application, whenever Java host objects are
 * accessed.
 * <p>
 * There are three predefined instances of host access policies:
 * <ul>
 * <li>{@link #EXPLICIT} - Java host methods or fields, must be public and be annotated with
 * {@link Export @Export} to make them accessible to the guest language.
 * <li>{@link #NONE} - Does not allow any access to methods or fields of host objects. Java host
 * objects may still be passed into a context, but they cannot be accessed.
 * <li>{@link #ALL} - Does allow full unrestricted access to public methods or fields of host
 * objects. Note that this policy allows unrestricted access to reflection. It is highly discouraged
 * from using this policy in environments where the guest application is not fully trusted.
 * </ul>
 * Custom host access policies can be created using {@link #newBuilder()}. The builder allows to
 * specify a custom export annotation and allowed and denied methods or fields.
 *
 * @since 1.0
 */
public final class HostAccess {

    private final String name;
    private final Set<Class<? extends Annotation>> annotations;
    private final Map<Class<?>, Boolean> excludeTypes;
    private final Set<AnnotatedElement> members;
    private final boolean allowPublic;
    final boolean allowArrayAccess;
    final boolean allowListAccess;
    volatile Object impl;

    /**
     * Predefined host access policy that allows access to public host methods or fields that were
     * annotated with {@linkplain Export @Export} and were declared in public class. This is the
     * default configuration if {@link Context.Builder#allowAllAccess(boolean)} is
     * <code>false</code>.
     * <p>
     * Equivalent of using the following builder configuration:
     *
     * <pre>
     * HostAccess.newBuilder().allowAccessAnnotatedBy(HostAccess.Export.class).build();
     * </pre>
     *
     * @since 1.0
     */
    public static final HostAccess EXPLICIT = newBuilder().allowAccessAnnotatedBy(HostAccess.Export.class).name("HostAccess.EXPLICIT").build();

    /**
     *
     * Predefined host access policy that allows full unrestricted access to public methods or
     * fields of public host classes. Note that this policy allows unrestricted access to
     * reflection. It is highly discouraged from using this policy in environments where the guest
     * application is not fully trusted. This is the default configuration if
     * {@link Context.Builder#allowAllAccess(boolean)} is <code>true</code>.
     * <p>
     * Equivalent of using the following builder configuration:
     *
     * <pre>
     * HostAccess.newBuilder().allowPublicAccess(true).allowArrayAccess(true).allowListAccess(true).build();
     * </pre>
     *
     * @since 1.0
     */
    public static final HostAccess ALL = newBuilder().allowPublicAccess(true).allowArrayAccess(true).allowListAccess(true).name("HostAccess.ALL").build();

    /**
     * Predefined host access policy that disallows any access to public host methods or fields.
     * <p>
     * Equivalent of using the following builder configuration:
     *
     * <pre>
     * HostAccess.newBuilder().build();
     * </pre>
     *
     * @since 1.0
     */
    public static final HostAccess NONE = newBuilder().name("HostAccess.NONE").build();

    HostAccess(Set<Class<? extends Annotation>> annotations, Map<Class<?>, Boolean> excludeTypes, Set<AnnotatedElement> members, String name, boolean allowPublic, boolean allowArrayAccess,
                    boolean allowListAccess) {
        // create defensive copies
        this.annotations = annotations != null ? new LinkedHashSet<>(annotations) : null;
        this.excludeTypes = excludeTypes != null ? new LinkedHashMap<>(excludeTypes) : null;
        this.members = members != null ? new LinkedHashSet<>(members) : null;
        this.name = name;
        this.allowPublic = allowPublic;
        this.allowArrayAccess = allowArrayAccess;
        this.allowListAccess = allowListAccess;
    }

    /**
     * Creates a new builder that allows to create a custom host access policy. The builder
     * configuration needs to be completed using the {@link Builder#build() method}.
     *
     * @since 1.0
     */
    public static Builder newBuilder() {
        return new HostAccess(null, null, null, null, false, false, false).new Builder();
    }

    boolean allowsAccess(AnnotatedElement member) {
        if (excludeTypes != null) {
            Class<?> owner = getDeclaringClass(member);
            for (Map.Entry<Class<?>, Boolean> entry : excludeTypes.entrySet()) {
                Class<?> ban = entry.getKey();
                if (entry.getValue()) {
                    // include subclasses
                    if (ban.isAssignableFrom(owner)) {
                        return false;
                    }
                } else {
                    if (ban == owner) {
                        return false;
                    }
                }
            }
        }
        if (allowPublic) {
            return true;
        }
        if (members != null && members.contains(member)) {
            return true;
        }
        if (annotations != null) {
            for (Class<? extends Annotation> ann : annotations) {
                if (hasAnnotation(member, ann)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * {@inheritDoc}
     *
     * @since 1.0
     */
    @Override
    public String toString() {
        return name == null ? super.toString() : name;
    }

    private static boolean hasAnnotation(AnnotatedElement member, Class<? extends Annotation> annotationType) {
        if (member instanceof Field) {
            Field f = (Field) member;
            return f.getAnnotation(annotationType) != null;
        }
        if (member instanceof Method) {
            Method m = (Method) member;
            return m.getAnnotation(annotationType) != null;
        }
        if (member instanceof Constructor) {
            Constructor<?> c = (Constructor<?>) member;
            return c.getAnnotation(annotationType) != null;
        }
        return false;
    }

    private static Class<?> getDeclaringClass(AnnotatedElement member) {
        if (member instanceof Field) {
            Field f = (Field) member;
            return f.getDeclaringClass();
        }
        if (member instanceof Method) {
            Method m = (Method) member;
            return m.getDeclaringClass();
        }
        if (member instanceof Constructor) {
            Constructor<?> c = (Constructor<?>) member;
            return c.getDeclaringClass();
        }
        return Object.class;
    }

    /**
     * Annotation used by the predefined {@link #EXPLICIT} access policy to mark public
     * constructors, methods and fields in public classes that should be accessible by the guest
     * application.
     * <p>
     * <b>Example</b> using a Java object from JavaScript:
     *
     * <pre>
     * public class JavaRecord {
     *     &#64;HostAccess.Export public int x;
     *
     *     &#64;HostAccess.Export
     *     public String name() {
     *         return "foo";
     *     }
     * }
     * try (Context context = Context.create()) {
     *     JavaRecord record = new JavaRecord();
     *     context.getBindings("js").putMember("javaRecord", record);
     *     context.eval("js", "javaRecord.x = 42");
     *     context.eval("js", "javaRecord.name()").asString().equals("foo");
     * }
     * </pre>
     *
     * @see Context.Builder#allowHostAccess(HostAccess)
     * @see HostAccess#EXPLICIT
     * @since 1.0
     */
    @Target({ElementType.CONSTRUCTOR, ElementType.FIELD, ElementType.METHOD})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Export {
    }

    /**
     * Builder to create a custom {@link HostAccess host access policy}.
     *
     * @since 1.0
     */
    public final class Builder {
        private final Set<Class<? extends Annotation>> annotations = new HashSet<>();
        private final Map<Class<?>, Boolean> excludeTypes = new HashMap<>();
        private final Set<AnnotatedElement> members = new HashSet<>();
        private boolean allowPublic;
        private boolean allowListAccess;
        private boolean allowArrayAccess;
        private String name;

        Builder() {
        }

        /**
         * Allows access to public constructors, methods or fields of public classes that were
         * annotated by the given annotation class.
         *
         * @since 1.0
         */
        public Builder allowAccessAnnotatedBy(Class<? extends Annotation> annotation) {
            Objects.requireNonNull(annotation);
            annotations.add(annotation);
            return this;
        }

        /**
         * Allows unrestricted access to all public constructors, methods or fields of public
         * classes. Note that this policy allows unrestricted access to reflection. It is highly
         * discouraged from using this option in environments where the guest application is not
         * fully trusted.
         *
         * @since 1.0
         */
        public Builder allowPublicAccess(boolean allow) {
            allowPublic = allow;
            return this;
        }

        /**
         * Allows access to a given constructor or method. Note that the method or constructor must
         * be public in order to have any effect.
         *
         * @since 1.0
         */
        public Builder allowAccess(Executable element) {
            Objects.requireNonNull(element);
            members.add(element);
            return this;
        }

        /**
         * Allows access to a given field. Note that the field must be public in order to have any
         * effect.
         *
         * @since 1.0
         */
        public Builder allowAccess(Field element) {
            Objects.requireNonNull(element);
            members.add(element);
            return this;
        }

        /**
         * Prevents access to members of given class and its subclasses.
         *
         * @param clazz the class to deny access to
         * @return this builder
         * @since 1.0
         */
        public Builder denyAccess(Class<?> clazz) {
            return denyAccess(clazz, true);
        }

        /**
         * Prevents access to members of given class.
         *
         * @param clazz the class to deny access to
         * @param includeSubclasses should subclasses be excuded as well?
         * @return this builder
         * @since 1.0
         */
        public Builder denyAccess(Class<?> clazz, boolean includeSubclasses) {
            Objects.requireNonNull(clazz);
            excludeTypes.put(clazz, includeSubclasses);
            return this;
        }

        /**
         * Allows the guest application to access arrays as values with
         * {@link Value#hasArrayElements() array elements}. By default no array access is allowed.
         *
         * @see Value#hasArrayElements()
         * @since 1.0
         */
        public Builder allowArrayAccess(boolean arrayAccess) {
            this.allowArrayAccess = arrayAccess;
            return this;
        }

        /**
         * Allows the guest application to access lists as values with
         * {@link Value#hasArrayElements() array elements}. By default no array access is allowed.
         *
         * @see Value#hasArrayElements()
         * @since 1.0
         */
        public Builder allowListAccess(boolean listAccess) {
            this.allowListAccess = listAccess;
            return this;
        }

        HostAccess.Builder name(String givenName) {
            this.name = givenName;
            return this;
        }

        /**
         * Creates an instance of the custom host access configuration.
         *
         * @since 1.0
         */
        public HostAccess build() {
            return new HostAccess(annotations, excludeTypes, members, name, allowPublic, allowArrayAccess, allowListAccess);
        }
    }
}
