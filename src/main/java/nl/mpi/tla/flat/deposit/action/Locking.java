/* 
 * Copyright (C) 2017 The Language Archive
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package nl.mpi.tla.flat.deposit.action;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import net.sf.saxon.s9api.XdmAtomicValue;
import net.sf.saxon.s9api.XdmItem;
import net.sf.saxon.s9api.XdmSequenceIterator;
import nl.mpi.tla.flat.deposit.Context;
import nl.mpi.tla.flat.deposit.DepositException;
import nl.mpi.tla.flat.deposit.sip.Collection;
import org.slf4j.LoggerFactory;

/**
 *
 * @author menzowi
 */
public class Locking extends AbstractAction {
    
    private static final org.slf4j.Logger logger = LoggerFactory.getLogger(Locking.class.getName());
    
    private static final String MEMO = "nl.mpi.tla.flat.deposit.action.Locking.LOCKED";
    private static final String FILE_MEMO = "nl.mpi.tla.flat.deposit.action.Locking.FILE_LOCKED";
    
    private static final Map<String,ReentrantLock> locks = new ConcurrentHashMap<>();
    
    @Override
    public boolean perform(Context context) throws DepositException {
        Set<ReentrantLock> locked = null;
        Set<OwnedFileLock> fileLocks = null;
        if (context.hasInMemory(MEMO)) {
            Object stored = context.getFromMemory(MEMO);
            if (!(stored instanceof Set<?> storedLocks)) {
                throw new DepositException("Invalid lock state in context memory");
            }
            locked = new HashSet<>();
            for (Object lock : storedLocks) {
                if (!(lock instanceof ReentrantLock reentrantLock)) {
                    throw new DepositException("Invalid lock entry in context memory");
                }
                locked.add(reentrantLock);
            }
            context.putInMemory(MEMO, locked);
        } else {
            locked = new HashSet<>();
            context.putInMemory(MEMO, locked);
            final Set<ReentrantLock> locksToRelease = locked;
            context.registerCleanup(() -> releaseAll(locksToRelease));
        }
        if (context.hasInMemory(FILE_MEMO)) {
            Object stored = context.getFromMemory(FILE_MEMO);
            if (!(stored instanceof Set<?> storedLocks))
                throw new DepositException("Invalid file lock state in context memory");
            fileLocks = new HashSet<>();
            for (Object lock : storedLocks) {
                if (!(lock instanceof OwnedFileLock owned))
                    throw new DepositException("Invalid file lock entry in context memory");
                fileLocks.add(owned);
            }
            context.putInMemory(FILE_MEMO, fileLocks);
        } else {
            fileLocks = new HashSet<>();
            context.putInMemory(FILE_MEMO, fileLocks);
            final Set<OwnedFileLock> fileLocksToRelease = fileLocks;
            context.registerCleanup(() -> releaseFiles(fileLocksToRelease));
        }
        final String lockDir = getParameter("lockDir", "");
        String mode = this.getParameter("mode", "lock");
        if (mode.equals("lock")) {
            for (XdmSequenceIterator<XdmItem> iter=(params.containsKey("what")?params.get("what"):new XdmAtomicValue("sip")).iterator();iter.hasNext();) {
                String what = iter.next().getStringValue();
                if (what.equals("sip")) {
                    String uri = context.getSIP().getFID(true).toString();
                    ReentrantLock lock = locks.computeIfAbsent(uri, ignored -> new ReentrantLock());
                    logger.debug("lock["+lock+"] sip["+uri+"]");
                    lock.lock();
                    locked.add(lock);
                    acquireFileLock(lockDir, uri, fileLocks);
                    logger.debug("locked["+lock+"] sip["+uri+"]");
                } else if (what.contains("collections")) {
                    // loop over collections
                    for (Collection col:context.getSIP().getCollections(!mode.contains("parent"))) {
                        String uri = col.getFID(true).toString();
                        ReentrantLock lock = locks.computeIfAbsent(uri, ignored -> new ReentrantLock());
                        logger.debug("lock["+lock+"] collection["+uri+"]");
                        lock.lock();
                        locked.add(lock);
                        acquireFileLock(lockDir, uri, fileLocks);
                        logger.debug("locked["+lock+"] collection["+uri+"]");
                    }
                } else {
                    ReentrantLock lock = locks.computeIfAbsent(what, ignored -> new ReentrantLock());
                    logger.debug("lock["+lock+"] something["+what+"]");
                    lock.lock();
                    locked.add(lock);
                    acquireFileLock(lockDir, what, fileLocks);
                    logger.debug("locked["+lock+"] something["+what+"]");
                }
            }
            return true;
        }
        if (mode.equals("unlock")) {
            releaseAll(locked);
            releaseFiles(fileLocks);
            return true;
        }
        logger.error("Unknown locking mode["+mode+"]!");
        return false;
    }

    private static void releaseAll(Set<ReentrantLock> locked) {
        for (ReentrantLock lock : new HashSet<>(locked)) {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
                logger.debug("unlocked[{}]", lock);
            }
        }
        locked.clear();
    }

    /**
     * Supplying lockDir enables host/container-wide locking through a shared
     * filesystem. Without it the historic in-JVM lock is retained for
     * compatibility; production workflows should always configure lockDir.
     */
    private static void acquireFileLock(final String lockDir, final String key, final Set<OwnedFileLock> held)
            throws DepositException {
        if (lockDir == null || lockDir.isBlank() || held.stream().anyMatch(lock -> lock.key().equals(key)))
            return;
        try {
            final Path directory = Path.of(lockDir);
            Files.createDirectories(directory);
            final String name = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8)));
            final FileChannel channel = FileChannel.open(directory.resolve(name + ".lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            held.add(new OwnedFileLock(key, channel, channel.lock()));
        } catch (Exception ex) {
            throw new DepositException("Couldn't acquire shared lock for " + key, ex);
        }
    }

    private static void releaseFiles(final Set<OwnedFileLock> held) {
        for (OwnedFileLock lock : new HashSet<>(held)) {
            try {
                lock.lock().release();
                lock.channel().close();
            } catch (Exception ex) {
                logger.error("Couldn't release shared lock for {}", lock.key(), ex);
            }
        }
        held.clear();
    }

    private record OwnedFileLock(String key, FileChannel channel, FileLock lock) { }
        
}
