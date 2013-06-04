/*
 * Copyright 2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.cache.internal;

import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.cache.internal.locklistener.FileLockListener;
import org.gradle.internal.Factory;
import org.gradle.util.GFileUtils;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import static org.gradle.internal.UncheckedException.throwAsUncheckedException;

/**
 * Uses file system locks on a lock file per target file. Each lock file is made up of 2 regions:
 *
 * <ul> <li>State region: 1 byte version field, 1 byte clean flag.</li> <li>Owner information region: 1 byte version field, utf-8 encoded owner process id, utf-8 encoded owner operation display
 * name.</li> </ul>
 */
public class DefaultFileLockManager implements FileLockManager {
    private static final Logger LOGGER = Logging.getLogger(DefaultFileLockManager.class);
    private static final int DEFAULT_LOCK_TIMEOUT = 60000;
    private static final byte STATE_REGION_PROTOCOL = 1;
    private static final int STATE_REGION_SIZE = 2;
    private static final int STATE_REGION_POS = 0;
    private static final byte INFORMATION_REGION_PROTOCOL = 3;
    private static final int INFORMATION_REGION_POS = STATE_REGION_POS + STATE_REGION_SIZE;
    public static final int INFORMATION_REGION_SIZE = 2052;
    public static final int INFORMATION_REGION_DESCR_CHUNK_LIMIT = 340;
    private final Set<File> lockedFiles = new CopyOnWriteArraySet<File>();
    private final ProcessMetaDataProvider metaDataProvider;
    private final int lockTimeoutMs;
    private FileLockListener fileLockListener;

    public DefaultFileLockManager(ProcessMetaDataProvider metaDataProvider, FileLockListener fileLockListener) {
        this(metaDataProvider, DEFAULT_LOCK_TIMEOUT, fileLockListener);
    }

    public DefaultFileLockManager(ProcessMetaDataProvider metaDataProvider, int lockTimeoutMs, FileLockListener fileLockListener) {
        this.metaDataProvider = metaDataProvider;
        this.lockTimeoutMs = lockTimeoutMs;
        this.fileLockListener = fileLockListener;
    }

    public FileLock lock(File target, LockMode mode, String targetDisplayName, Runnable whenContended) throws LockTimeoutException {
        return lock(target, mode, targetDisplayName, "", whenContended);
    }

    public FileLock lock(File target, LockMode mode, String targetDisplayName, String operationDisplayName, Runnable whenContended) {
        if (mode == LockMode.None) {
            throw new UnsupportedOperationException(String.format("No %s mode lock implementation available.", mode));
        }
        File canonicalTarget = GFileUtils.canonicalise(target);
        if (!lockedFiles.add(canonicalTarget)) {
            throw new IllegalStateException(String.format("Cannot lock %s as it has already been locked by this process.", targetDisplayName));
        }
        try {
            int port = fileLockListener.reservePort();
            DefaultFileLock newLock = new DefaultFileLock(canonicalTarget, mode, targetDisplayName, operationDisplayName, port);
            fileLockListener.lockCreated(canonicalTarget, whenContended);
            return newLock;
        } catch (Throwable t) {
            lockedFiles.remove(canonicalTarget);
            throw throwAsUncheckedException(t);
        }
    }

    private class OwnerInfo {
        int port;
        String pid;
        String operation;
    }

    private class DefaultFileLock extends AbstractFileAccess implements FileLock {
        private final File lockFile;
        private final File target;
        private final LockMode mode;
        private final String displayName;
        private final String operationDisplayName;
        private java.nio.channels.FileLock lock;
        private RandomAccessFile lockFileAccess;
        private boolean integrityViolated;
        private int port;

        public DefaultFileLock(File target, LockMode mode, String displayName, String operationDisplayName, int port) throws Throwable {
            this.port = port;
            if (mode == LockMode.None) {
                throw new UnsupportedOperationException("Locking mode None is not supported.");
            }

            this.target = target;

            this.displayName = displayName;
            this.operationDisplayName = operationDisplayName;
            if (target.isDirectory()) {
                lockFile = new File(target, target.getName() + ".lock");
            } else {
                lockFile = new File(target.getParentFile(), target.getName() + ".lock");
            }

            GFileUtils.mkdirs(lockFile.getParentFile());
            lockFile.createNewFile();
            lockFileAccess = new RandomAccessFile(lockFile, "rw");
            try {
                lock = lock(mode);
                integrityViolated = !getUnlockedCleanly();
            } catch (Throwable t) {
                // Also releases any locks
                lockFileAccess.close();
                throw t;
            }

            this.mode = lock.isShared() ? LockMode.Shared : LockMode.Exclusive;
        }

        public boolean isLockFile(File file) {
            return file.equals(lockFile);
        }

        public boolean getUnlockedCleanly() {
            assertOpen();
            try {
                lockFileAccess.seek(STATE_REGION_POS + 1);
                if (!lockFileAccess.readBoolean()) {
                    // Process has crashed while updating target file
                    return false;
                }
            } catch (EOFException e) {
                // Process has crashed writing to lock file
                return false;
            } catch (Exception e) {
                throw throwAsUncheckedException(e);
            }

            return true;
        }

        public <T> T readFile(Factory<? extends T> action) throws LockTimeoutException, FileIntegrityViolationException {
            assertOpenAndIntegral();
            return action.create();
        }

        public void updateFile(Runnable action) throws LockTimeoutException, FileIntegrityViolationException {
            assertOpenAndIntegral();
            doWriteAction(action);
        }

        public void writeFile(Runnable action) throws LockTimeoutException {
            assertOpen();
            doWriteAction(action);
        }

        private void doWriteAction(Runnable action) {
            if (mode != LockMode.Exclusive) {
                throw new InsufficientLockModeException("An exclusive lock is required for this operation");
            }

            try {
                integrityViolated = true;
                markDirty();
                action.run();
                markClean();
                integrityViolated = false;
            } catch (Throwable t) {
                throw throwAsUncheckedException(t);
            }
        }

        private void assertOpen() {
            if (lock == null) {
                throw new IllegalStateException("This lock has been closed.");
            }
        }

        private void assertOpenAndIntegral() {
            assertOpen();
            if (integrityViolated) {
                throw new FileIntegrityViolationException(String.format("The file '%s' was not unlocked cleanly", target));
            }
        }

        private void markClean() throws IOException {
            lockFileAccess.seek(STATE_REGION_POS);
            lockFileAccess.writeByte(STATE_REGION_PROTOCOL);
            lockFileAccess.writeBoolean(true);
            assert lockFileAccess.getFilePointer() == STATE_REGION_SIZE + STATE_REGION_POS;
        }

        private void markDirty() throws IOException {
            lockFileAccess.seek(STATE_REGION_POS);
            lockFileAccess.writeByte(STATE_REGION_PROTOCOL);
            lockFileAccess.writeBoolean(false);
            assert lockFileAccess.getFilePointer() == STATE_REGION_SIZE + STATE_REGION_POS;
        }

        public void close() {
            if (lockFileAccess == null) {
                return;
            }
            try {
                LOGGER.debug("Releasing lock on {}.", displayName);
                lockedFiles.remove(target);
                // Also releases any locks
                try {
                    if (lock != null && !lock.isShared()) {
                        // Discard information region
                        java.nio.channels.FileLock info;
                        try {
                            info = lockInformationRegion(LockMode.Exclusive);
                        } catch (InterruptedException e) {
                            throw throwAsUncheckedException(e);
                        }
                        try {
                            lockFileAccess.setLength(INFORMATION_REGION_POS);
                        } finally {
                            info.release();
                        }
                    }
                } finally {
                    lockFileAccess.close();
                }
            } catch (IOException e) {
                LOGGER.warn("Error releasing lock on {}: {}", displayName, e);
            } finally {
                lock = null;
                lockFileAccess = null;
                fileLockListener.lockClosed(target);
            }
        }

        public LockMode getMode() {
            return mode;
        }

        private java.nio.channels.FileLock lock(FileLockManager.LockMode lockMode) throws Throwable {
            LOGGER.debug("Waiting to acquire {} lock on {}.", lockMode.toString().toLowerCase(), displayName);
            long timeout = System.currentTimeMillis() + lockTimeoutMs;

            // Lock the state region, with the requested mode
            java.nio.channels.FileLock stateRegionLock = lockStateRegion(lockMode, timeout);
            if (stateRegionLock == null) {
                OwnerInfo ownerInfo = readInformationRegion();
                throw new LockTimeoutException(String.format("Timeout waiting to lock %s. It is currently in use by another Gradle instance.%nOwner PID: %s%nOur PID: %s%nOwner Operation: %s%nOur operation: %s%nLock file: %s",
                        displayName, ownerInfo.pid, metaDataProvider.getProcessIdentifier(), ownerInfo.operation, operationDisplayName, lockFile));
            }

            try {
                if (lockFileAccess.length() > 0) {
                    lockFileAccess.seek(STATE_REGION_POS);
                    if (lockFileAccess.readByte() != STATE_REGION_PROTOCOL) {
                        throw new IllegalStateException(String.format("Unexpected lock protocol found in lock file '%s' for %s.", lockFile, displayName));
                    }
                }

                if (!stateRegionLock.isShared()) {
                    // We have an exclusive lock (whether we asked for it or not).
                    // Update the state region
                    if (lockFileAccess.length() < STATE_REGION_SIZE) {
                        // File did not exist before locking
                        lockFileAccess.seek(STATE_REGION_POS);
                        lockFileAccess.writeByte(STATE_REGION_PROTOCOL);
                        lockFileAccess.writeBoolean(false);
                    }
                    // Acquire an exclusive lock on the information region and write our details there
                    java.nio.channels.FileLock informationRegionLock = lockInformationRegion(LockMode.Exclusive);
                    if (informationRegionLock == null) {
                        throw new IllegalStateException(String.format("Timeout waiting to lock the information region for lock %s", displayName));
                    }
                    // check that the length of the reserved region is enough for storing our content
                    try {
                        lockFileAccess.seek(INFORMATION_REGION_POS);
                        lockFileAccess.writeByte(INFORMATION_REGION_PROTOCOL);
                        lockFileAccess.writeInt(port);
                        lockFileAccess.writeUTF(trimIfNecessary(metaDataProvider.getProcessIdentifier()));
                        lockFileAccess.writeUTF(trimIfNecessary(operationDisplayName));
                        lockFileAccess.setLength(lockFileAccess.getFilePointer());
                    } finally {
                        informationRegionLock.release();
                    }
                }
            } catch (Throwable t) {
                stateRegionLock.release();
                throw t;
            }

            LOGGER.debug("Lock acquired.");
            return stateRegionLock;
        }

        private OwnerInfo readInformationRegion() throws IOException, InterruptedException {
            // Can't acquire lock, get details of owner to include in the error message
            OwnerInfo out = new OwnerInfo();
            out.pid = "unknown";
            out.operation = "unknown";
            out.port = -1;
            java.nio.channels.FileLock informationRegionLock = lockInformationRegion(LockMode.Shared);
            if (informationRegionLock == null) {
                LOGGER.debug("Could not lock information region for {}. Ignoring.", displayName);
            } else {
                try {
                    if (lockFileAccess.length() <= INFORMATION_REGION_POS) {
                        LOGGER.debug("Lock file for {} is too short to contain information region. Ignoring.", displayName);
                    } else {
                        lockFileAccess.seek(INFORMATION_REGION_POS);
                        if (lockFileAccess.readByte() != INFORMATION_REGION_PROTOCOL) {
                            throw new IllegalStateException(String.format("Unexpected lock protocol found in lock file '%s' for %s.", lockFile, displayName));
                        }
                        out.port = lockFileAccess.readInt();
                        out.pid = lockFileAccess.readUTF();
                        out.operation = lockFileAccess.readUTF();
                        LOGGER.debug("Read following information from the file lock info region. Port: {}, owner: {}, operation: {}", out.port, out.pid, out.operation);
                    }
                } finally {
                    informationRegionLock.release();
                }
            }
            return out;
        }

        private String trimIfNecessary(String inputString) {
            if(inputString.length() > INFORMATION_REGION_DESCR_CHUNK_LIMIT){
                return inputString.substring(0, INFORMATION_REGION_DESCR_CHUNK_LIMIT);
            } else {
                return inputString;
            }
        }

        private java.nio.channels.FileLock lockStateRegion(LockMode lockMode, final long timeout) throws IOException, InterruptedException {
            do {
                java.nio.channels.FileLock fileLock = lockRegion(lockMode, STATE_REGION_POS, STATE_REGION_SIZE);
                if (fileLock != null) {
                    return fileLock;
                }
                if (port != -1) { //we don't like the assumption about the port very much
                    OwnerInfo ownerInfo = readInformationRegion();
                    if (ownerInfo.port != -1) {
                        LOGGER.info("The file lock is held by a different Gradle process. Will attempt to ping owner at port {}", ownerInfo.port);
                        FileLockCommunicator.pingOwner(ownerInfo.port, target);
                    } else {
                        LOGGER.info("The file lock is held by a different Gradle process. I was unable to read on which port the owner listens for lock access requests.");
                    }
                }
                Thread.sleep(200L);
            } while (System.currentTimeMillis() < timeout);
            return null;
        }

        private java.nio.channels.FileLock lockInformationRegion(LockMode lockMode) throws IOException, InterruptedException {
            return lockRegion(lockMode, INFORMATION_REGION_POS, INFORMATION_REGION_SIZE - INFORMATION_REGION_POS);
        }

        private java.nio.channels.FileLock lockRegion(LockMode lockMode, long start, long size) throws IOException, InterruptedException {
            return lockFileAccess.getChannel().tryLock(start, size, lockMode == LockMode.Shared);
        }
    }
}