/*
 * Copyright (c) 2016, 2017, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.driver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

final class MacroOption {
    enum MacroOptionKind {
        Language("languages"),
        Tool("tools"),
        Builtin("");

        final String subdir;

        MacroOptionKind(String subdir) {
            this.subdir = subdir;
        }

        static MacroOptionKind fromSubdir(String subdir) {
            for (MacroOptionKind kind : MacroOptionKind.values()) {
                if (kind.subdir.equals(subdir)) {
                    return kind;
                }
            }
            throw new InvalidMacroException("No MacroOptionKind for subDir: " + subdir);
        }
    }

    Path getImageJarsDirectory() {
        return optionDirectory;
    }

    Path getBuilderJarsDirectory() {
        return optionDirectory.resolve("builder");
    }

    String getOptionName() {
        return optionName;
    }

    private static final String macroOptionPrefix = "--";

    String getDescription(boolean commandLineStyle) {
        StringBuilder sb = new StringBuilder();
        if (commandLineStyle) {
            sb.append(macroOptionPrefix);
        }
        sb.append(kind.name()).append(":").append(getOptionName());
        return sb.toString();
    }

    @SuppressWarnings("serial")
    static final class InvalidMacroException extends RuntimeException {
        InvalidMacroException(String arg0) {
            super(arg0);
        }
    }

    @SuppressWarnings("serial")
    static final class VerboseInvalidMacroException extends RuntimeException {
        private final MacroOptionKind forKind;
        private final MacroOption context;

        VerboseInvalidMacroException(String arg0, MacroOption context) {
            this(arg0, null, context);
        }

        VerboseInvalidMacroException(String arg0, MacroOptionKind forKind, MacroOption context) {
            super(arg0);
            this.forKind = forKind;
            this.context = context;

        }

        public String getMessage(Registry registry) {
            StringBuilder sb = new StringBuilder();
            String message = super.getMessage();
            if (context != null) {
                sb.append(context.getDescription(false) + " contains ");
                if (!message.isEmpty()) {
                    sb.append(Character.toLowerCase(message.charAt(0)));
                    sb.append(message.substring(1));
                }
            } else {
                sb.append(message);
            }
            Consumer<String> lineOut = s -> sb.append("\n" + s);
            registry.showOptions(forKind, context == null, lineOut);
            return sb.toString();
        }
    }

    @SuppressWarnings("serial")
    static final class AddedTwiceException extends RuntimeException {
        private final MacroOption option;
        private final MacroOption context;

        AddedTwiceException(MacroOption option, MacroOption context) {
            this.option = option;
            this.context = context;
        }

        @Override
        public String getMessage() {
            StringBuilder sb = new StringBuilder();
            if (context != null) {
                sb.append("MacroOption ").append(context.getDescription(false));
                if (option.equals(context)) {
                    sb.append(" cannot require itself");
                } else {
                    sb.append(" requires ").append(option.getDescription(false)).append(" more than once");
                }

            } else {
                sb.append("Command line option ").append(option.getDescription(true));
                sb.append(" used more than once");
            }
            return sb.toString();
        }
    }

    static final class EnabledOption {
        private final MacroOption option;
        private final String optionArg;

        private EnabledOption(MacroOption option, String optionArg) {
            this.option = option;
            this.optionArg = optionArg;
        }

        private String resolvePropertyValue(String val) {
            String resultVal = val;
            if (optionArg != null) {
                /* Substitute ${*} -> optionArg in resultVal (always possible) */
                resultVal = resultVal.replace("${*}", optionArg);
                /*
                 * If optionArg consists of "<argName>:<argValue>,..." additionally perform
                 * substitutions of kind ${<argName>} -> <argValue> on resultVal.
                 */
                for (String argNameValue : optionArg.split(",")) {
                    String[] splitted = argNameValue.split(":");
                    if (splitted.length == 2) {
                        String argName = splitted[0];
                        String argValue = splitted[1];
                        if (!argName.isEmpty()) {
                            resultVal = resultVal.replace("${" + argName + "}", argValue);
                        }
                    }
                }
            }
            /* Substitute ${.} -> absolute path to optionDirectory */
            resultVal = resultVal.replace("${.}", getOption().optionDirectory.toString());
            return resultVal;
        }

        String getProperty(String key, String defaultVal) {
            String val = option.properties.get(key);
            if (val == null) {
                return defaultVal;
            }
            return resolvePropertyValue(val);
        }

        String getProperty(String key) {
            return getProperty(key, null);
        }

        boolean forEachPropertyValue(String propertyKey, Consumer<String> target) {
            String propertyValueRaw = option.properties.get(propertyKey);
            if (propertyValueRaw != null) {
                for (String propertyValue : Arrays.asList(propertyValueRaw.split(" "))) {
                    target.accept(resolvePropertyValue(propertyValue));
                }
                return true;
            }
            return false;
        }

        MacroOption getOption() {
            return option;
        }
    }

    static final class Registry {
        private final Map<MacroOptionKind, Map<String, MacroOption>> supported = new HashMap<>();
        private final LinkedHashSet<EnabledOption> enabled = new LinkedHashSet<>();

        private static Map<MacroOptionKind, Map<String, MacroOption>> collectMacroOptions(Path rootDir) throws IOException {
            Map<MacroOptionKind, Map<String, MacroOption>> result = new HashMap<>();
            for (MacroOptionKind kind : MacroOptionKind.values()) {
                if (kind.subdir.isEmpty()) {
                    continue;
                }
                Path optionDir = rootDir.resolve(kind.subdir);
                Map<String, MacroOption> collectedOptions = Collections.emptyMap();
                if (Files.isDirectory(optionDir)) {
                    collectedOptions = Files.list(optionDir).filter(Files::isDirectory)
                                    .map(MacroOption::create).filter(Objects::nonNull)
                                    .collect(Collectors.toMap(MacroOption::getOptionName, Function.identity()));
                }
                result.put(kind, collectedOptions);
            }
            return result;
        }

        Registry(Path rootDir) {
            addMacroOptionRoot(rootDir);
        }

        void addMacroOptionRoot(Path rootDir) {
            /* Discover MacroOptions and add to supported */
            try {
                collectMacroOptions(rootDir).forEach((optionKind, optionMap) -> {
                    Map<String, MacroOption> existingOptionMap = supported.get(optionKind);
                    if (existingOptionMap == null) {
                        supported.put(optionKind, optionMap);
                    } else {
                        existingOptionMap.putAll(optionMap);
                    }
                });
            } catch (IOException e) {
                throw new InvalidMacroException("Error while discovering supported MacroOptions in " + rootDir + ": " + e.getMessage());
            }
        }

        MacroOption addBuiltin(String optionName) {
            MacroOption builtin = new MacroOption(optionName);
            supported.computeIfAbsent(MacroOptionKind.Builtin, key -> new HashMap<>()).put(optionName, builtin);
            return builtin;
        }

        void showOptions(MacroOptionKind forKind, boolean commandLineStyle, Consumer<String> lineOut) {
            List<String> optionsToShow = new ArrayList<>();
            for (MacroOptionKind kind : MacroOptionKind.values()) {
                if (forKind != null && !kind.equals(forKind)) {
                    continue;
                }
                for (MacroOption option : supported.get(kind).values()) {
                    if (!option.kind.subdir.isEmpty()) {
                        String linePrefix = "    ";
                        if (commandLineStyle) {
                            linePrefix += macroOptionPrefix;
                        }
                        optionsToShow.add(linePrefix + option);
                    }
                }
            }
            if (!optionsToShow.isEmpty()) {
                StringBuilder sb = new StringBuilder().append("Available ");
                if (forKind != null) {
                    sb.append(forKind.name()).append(' ');
                } else {
                    sb.append("macro-");
                }
                lineOut.accept(sb.append("options are:").toString());
                optionsToShow.forEach(lineOut);
            }
        }

        boolean enableOption(String optionString, HashSet<MacroOption> addedCheck, MacroOption context) {
            String specString;
            if (context == null) {
                if (optionString.startsWith(macroOptionPrefix)) {
                    specString = optionString.substring(macroOptionPrefix.length());
                } else {
                    return false;
                }
            } else {
                specString = optionString;
            }

            String[] specParts = specString.split(":", 2);
            if (specParts.length != 2) {
                if (context == null) {
                    return false;
                } else {
                    throw new VerboseInvalidMacroException("Invalid option specification: " + optionString, context);
                }
            }

            MacroOptionKind kindPart;
            try {
                kindPart = MacroOptionKind.valueOf(specParts[0]);
            } catch (Exception e) {
                if (context == null) {
                    return false;
                } else {
                    throw new VerboseInvalidMacroException("Unknown kind in option specification: " + optionString, context);
                }
            }

            String specNameParts = specParts[1];
            if (specNameParts.isEmpty()) {
                throw new VerboseInvalidMacroException("Empty option specification: " + optionString, kindPart, context);
            }

            String[] parts = specNameParts.split("=", 2);
            String optionName = parts[0];
            MacroOption option = supported.get(kindPart).get(optionName);
            if (option != null) {
                String optionArg = parts.length == 2 ? parts[1] : null;
                enableResolved(option, optionArg, addedCheck, context);
            } else {
                throw new VerboseInvalidMacroException("Unknown name in option specification: " + kindPart + ":" + optionName, kindPart, context);
            }
            return true;
        }

        private void enableResolved(MacroOption option, String optionArg, HashSet<MacroOption> addedCheck, MacroOption context) {
            if (addedCheck.contains(option)) {
                if (option.kind.equals(MacroOptionKind.Builtin)) {
                    return;
                }
                throw new AddedTwiceException(option, context);
            }
            addedCheck.add(option);
            EnabledOption enabledOption = new EnabledOption(option, optionArg);
            String requires = enabledOption.getProperty("Requires", "");
            if (!requires.isEmpty()) {
                for (String specString : requires.split(" ")) {
                    enableOption(specString, addedCheck, option);
                }
            }

            MacroOption truffleOption = supported.get(MacroOptionKind.Tool).get("truffle");
            if (option.kind.equals(MacroOptionKind.Language) && !addedCheck.contains(truffleOption)) {
                /*
                 * Every language requires Truffle. If it is not specified explicitly as a
                 * requirement, add it automatically.
                 */
                enableResolved(truffleOption, null, addedCheck, context);
            }
            enabled.add(enabledOption);
        }

        LinkedHashSet<EnabledOption> getEnabledOptions(MacroOptionKind kind) {
            return enabled.stream().filter(eo -> kind.equals(eo.option.kind)).collect(Collectors.toCollection(LinkedHashSet::new));
        }

        LinkedHashSet<EnabledOption> getEnabledOptions() {
            return enabled;
        }

        EnabledOption getEnabledOption(MacroOption option) {
            return enabled.stream().filter(eo -> eo.getOption().equals(option)).findFirst().orElse(null);
        }

        void applyOptions(NativeImage nativeImage) {
            for (EnabledOption enabledOption : getEnabledOptions()) {
                if (enabledOption.getOption().kind.equals(MacroOptionKind.Builtin)) {
                    continue;
                }

                if (Files.isDirectory(enabledOption.getOption().getBuilderJarsDirectory())) {
                    NativeImage.getJars(enabledOption.getOption().getBuilderJarsDirectory()).forEach(nativeImage::addImageBuilderClasspath);
                }
                NativeImage.getJars(enabledOption.getOption().getImageJarsDirectory()).forEach(nativeImage::addImageClasspath);

                String imageName = enabledOption.getProperty("ImageName");
                if (imageName != null) {
                    nativeImage.addImageBuilderArg(NativeImage.oHName + imageName);
                }

                String launcherClass = enabledOption.getProperty("LauncherClass");
                if (launcherClass != null) {
                    nativeImage.addImageBuilderArg(NativeImage.oHClass + launcherClass);
                }

                enabledOption.forEachPropertyValue("JavaArgs", nativeImage::addImageBuilderJavaArgs);
                enabledOption.forEachPropertyValue("Args", nativeImage::addImageBuilderArg);
            }
        }
    }

    private final String optionName;
    private final Path optionDirectory;

    final MacroOptionKind kind;
    private final Map<String, String> properties;

    private static MacroOption create(Path macroOptionDirectory) {
        try {
            return new MacroOption(macroOptionDirectory);
        } catch (Exception e) {
            return null;
        }
    }

    private MacroOption(Path optionDirectory) {
        this.kind = MacroOptionKind.fromSubdir(optionDirectory.getParent().getFileName().toString());
        this.optionName = optionDirectory.getFileName().toString();
        this.optionDirectory = optionDirectory;
        this.properties = NativeImage.loadProperties(optionDirectory.resolve("native-image.properties"));
    }

    private MacroOption(String optionName) {
        this.kind = MacroOptionKind.Builtin;
        this.optionName = optionName;
        this.optionDirectory = null;
        this.properties = Collections.emptyMap();
    }

    @Override
    public String toString() {
        return getDescription(false);
    }
}
