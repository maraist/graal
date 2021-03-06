/*
 * Copyright (c) 2013, 2017, Oracle and/or its affiliates. All rights reserved.
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

package com.oracle.svm.hosted.image;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;

import org.graalvm.compiler.debug.DebugContext;

import com.oracle.svm.core.c.NativeImageHeaderPreamble;
import com.oracle.svm.core.util.VMError;
import com.oracle.svm.hosted.FeatureImpl.BeforeImageWriteAccessImpl;
import com.oracle.svm.hosted.c.NativeLibraries;
import com.oracle.svm.hosted.c.codegen.CSourceCodeWriter;
import com.oracle.svm.hosted.meta.HostedMetaAccess;
import com.oracle.svm.hosted.meta.HostedMethod;
import com.oracle.svm.hosted.meta.HostedUniverse;

public class SharedLibraryViaCCBootImage extends NativeBootImageViaCC {

    public SharedLibraryViaCCBootImage(HostedUniverse universe, HostedMetaAccess metaAccess, NativeLibraries nativeLibs, NativeImageHeap heap, NativeImageCodeCache codeCache,
                    List<HostedMethod> entryPoints) {
        super(NativeImageKind.SHARED_LIBRARY, universe, metaAccess, nativeLibs, heap, codeCache, entryPoints);
    }

    @Override
    public String[] makeLaunchCommand(NativeImageKind k, String imageName, Path binPath, Path workPath, Method method) {
        throw VMError.unimplemented();
    }

    @Override
    public Path write(DebugContext debug, Path outputDirectory, Path tempDirectory, String imageName, BeforeImageWriteAccessImpl config) {
        Path imagePath = super.write(debug, outputDirectory, tempDirectory, imageName, config);
        writeGraalIsolateHeader(outputDirectory);
        writeHeaderFile(outputDirectory.resolve(imageName + ".h"), imageName, false);
        writeHeaderFile(outputDirectory.resolve(imageName + "_dynamic.h"), imageName, true);
        return imagePath;
    }

    private static void writeGraalIsolateHeader(Path outputDirectory) {
        CSourceCodeWriter writer = new CSourceCodeWriter(outputDirectory);
        NativeImageHeaderPreamble.read("/" + DEFAULT_HEADER_FILE_NAME)
                        .forEach(writer::appendln);
        writer.writeFile(DEFAULT_HEADER_FILE_NAME, false);
    }
}
