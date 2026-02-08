/*
 * Copyright (c) 2010-2024 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * Modified for GraalVM native image compatibility by yaac.
 * GraalVM native image only allows predefined classes to be loaded once by a single
 * class loader. This patch caches successfully defined clone classes in a static map
 * so that subsequent CloningClassLoaders can reuse them instead of trying to defineClass again.
 */
package org.eclipse.sisu.space;

import java.lang.reflect.Modifier;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.eclipse.sisu.inject.DeferredClass;
import org.eclipse.sisu.space.asm.ClassWriter;
import org.eclipse.sisu.space.asm.MethodVisitor;
import org.eclipse.sisu.space.asm.Opcodes;

public final class CloningClassSpace
    extends URLClassSpace
{
    private static final String CLONE_MARKER = "$__sisu";

    private int cloneCount;

    public CloningClassSpace( final ClassSpace parent )
    {
        super( AccessController.doPrivileged( new PrivilegedAction<ClassLoader>()
        {
            public ClassLoader run()
            {
                return new CloningClassLoader( parent );
            }
        } ), null );
    }

    public DeferredClass<?> cloneClass( final String name )
    {
        final StringBuilder buf = new StringBuilder();
        if ( name.startsWith( "java" ) )
        {
            buf.append( '$' );
        }
        return deferLoadClass( buf.append( name ).append( CLONE_MARKER ).append( ++cloneCount ).toString() );
    }

    public static String originalName( final String proxyName )
    {
        final int cloneMarker = proxyName.lastIndexOf( CLONE_MARKER );
        if ( cloneMarker < 0 )
        {
            return proxyName;
        }
        for ( int i = cloneMarker + CLONE_MARKER.length(), end = proxyName.length(); i < end; i++ )
        {
            final char c = proxyName.charAt( i );
            if ( c < '0' || c > '9' )
            {
                return proxyName;
            }
        }
        return proxyName.substring( '$' == proxyName.charAt( 0 ) ? 1 : 0, cloneMarker );
    }

    private static final class CloningClassLoader
        extends ClassLoader
    {
        // Global cache of clone classes defined by any CloningClassLoader.
        // GraalVM native image only allows predefined classes to be defined once,
        // so we cache them here for reuse by subsequent CloningClassLoader instances.
        private static final ConcurrentMap<String, Class<?>> DEFINED_CLONES = new ConcurrentHashMap<>();

        private final ClassSpace parent;

        CloningClassLoader( final ClassSpace parent )
        {
            this.parent = parent;
        }

        @Override
        public String toString()
        {
            return parent.toString();
        }

        @Override
        protected synchronized Class<?> loadClass( final String name, final boolean resolve )
            throws ClassNotFoundException
        {
            if ( !name.contains( CLONE_MARKER ) )
            {
                try
                {
                    return parent.loadClass( name );
                }
                catch ( final TypeNotPresentException e )
                {
                    throw new ClassNotFoundException( name );
                }
            }
            // Check cache first before attempting to define
            final Class<?> cached = DEFINED_CLONES.get( name );
            if ( cached != null )
            {
                return cached;
            }
            return super.loadClass( name, resolve );
        }

        @Override
        protected Class<?> findClass( final String name )
            throws ClassNotFoundException
        {
            final String proxyName = name.replace( '.', '/' );
            final String superName = originalName( proxyName );

            if ( superName.equals( proxyName ) )
            {
                throw new ClassNotFoundException( name );
            }

            // Check cache again (may have been populated between loadClass and findClass)
            final Class<?> cached = DEFINED_CLONES.get( name );
            if ( cached != null )
            {
                return cached;
            }

            final ClassWriter cw = new ClassWriter( 0 );
            cw.visit( Opcodes.V1_6, Modifier.PUBLIC, proxyName, null, superName, null );
            final MethodVisitor mv = cw.visitMethod( Modifier.PUBLIC, "<init>", "()V", null, null );

            mv.visitCode();
            mv.visitVarInsn( Opcodes.ALOAD, 0 );
            mv.visitMethodInsn( Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false );
            mv.visitInsn( Opcodes.RETURN );
            mv.visitMaxs( 1, 1 );
            mv.visitEnd();
            cw.visitEnd();

            final byte[] buf = cw.toByteArray();

            try
            {
                final Class<?> clazz = defineClass( name, buf, 0, buf.length );
                DEFINED_CLONES.put( name, clazz );
                return clazz;
            }
            catch ( Error e )
            {
                // GraalVM native image: predefined class already loaded by another class loader.
                // Check cache one more time (another thread may have populated it).
                final Class<?> existing = DEFINED_CLONES.get( name );
                if ( existing != null )
                {
                    return existing;
                }
                // Cannot find a previously defined clone - this is a fatal error in native image.
                throw new ClassNotFoundException( name + " (predefined class conflict in native image)", e );
            }
        }
    }
}
