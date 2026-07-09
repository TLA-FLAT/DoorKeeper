/*
 * Copyright (C) 2015-2017 The Language Archive
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package nl.mpi.tla.flat.deposit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Curated messages intended for depositors rather than administrators. */
public final class UserLog {

    private static final Logger METADATA = LoggerFactory.getLogger("nl.mpi.tla.flat.deposit.user.metadata");
    private static final Logger FILE = LoggerFactory.getLogger("nl.mpi.tla.flat.deposit.user.file");
    private static final Logger SYSTEM = LoggerFactory.getLogger("nl.mpi.tla.flat.deposit.user.system");

    private UserLog() {
    }

    public static void metadataInfo(String message) { METADATA.info(message); }
    public static void metadataWarning(String message) { METADATA.warn(message); }
    public static void metadataError(String message) { METADATA.error(message); }
    public static void fileInfo(String message) { FILE.info(message); }
    public static void fileWarning(String message) { FILE.warn(message); }
    public static void fileError(String message) { FILE.error(message); }
    public static void systemError(String message) { SYSTEM.error(message); }
}
