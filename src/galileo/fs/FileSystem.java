/*
Copyright (c) 2013, Colorado State University
All rights reserved.

Redistribution and use in source and binary forms, with or without modification,
are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.
2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

This software is provided by the copyright holders and contributors "as is" and
any express or implied warranties, including, but not limited to, the implied
warranties of merchantability and fitness for a particular purpose are
disclaimed. In no event shall the copyright holder or contributors be liable for
any direct, indirect, incidental, special, exemplary, or consequential damages
(including, but not limited to, procurement of substitute goods or services;
loss of use, data, or profits; or business interruption) however caused and on
any theory of liability, whether in contract, strict liability, or tort
(including negligence or otherwise) arising in any way out of the use of this
software, even if advised of the possibility of such damage.
*/

package galileo.fs;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;

import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import galileo.dataset.Block;
import galileo.dataset.Metadata;
import galileo.serialization.SerializationException;
import galileo.serialization.Serializer;
import galileo.util.PerformanceTimer;

public abstract class FileSystem implements PhysicalGraph {

    private static final Logger logger = Logger.getLogger("galileo");

    public static final String BLOCK_EXTENSION = ".gblock";

    protected File storageDirectory;
    private boolean readOnly;

    public FileSystem(String storageRoot)
    throws FileSystemException, IOException {
        initialize(storageRoot);
    }

    protected void initialize(String storageRoot)
    throws FileSystemException, IOException {
        logger.info("Initializing Galileo File System.");
        logger.info("Storage directory: " + storageRoot);

        /* Ensure the storage directory exists. */
        storageDirectory = new File(storageRoot);
        if (!storageDirectory.exists()) {
            logger.warning("Root storage directory does not exist.  " +
                    "Attempting to create.");

            if (!storageDirectory.mkdirs()) {
                throw new FileSystemException("Unable to create storage " +
                    "directory.");
            }
        }

        logger.info("Free space: " + getFreeSpace());

        /* Verify permissions. */
        boolean read, write, execute;
        read    = storageDirectory.canRead();
        write   = storageDirectory.canWrite();
        execute = storageDirectory.canExecute();

        logger.info("File system permissions: " +
                (read ? 'r' : "") +
                (write ? 'w' : "") +
                (execute ? 'x' : ""));

        if (!read) {
            throw new FileSystemException("Cannot read storage directory.");
        }

        if (!execute) {
            throw new FileSystemException("Storage Directory " +
                    "is not Executable.");
        }

        readOnly = false;
        if (!write) {
            logger.warning("Storage directory is read-only.  Starting " +
                    "file system in read-only mode.");
            readOnly = true;
        }
    }

    /**
     * Scans a directory (and its subdirectories) for blocks.
     *
     * @param directory
     *     Directory to scan for blocks.
     *
     * @return ArrayList of String paths to blocks on disk.
     */
    protected List<String> scanDirectory(File directory) {
        List<String> blockPaths = new ArrayList<String>();
        scanSubDirectory(directory, blockPaths);
        return blockPaths;
    }

    /**
     * Scans a directory (and its subdirectories) for blocks.
     *
     * @param directory
     *     Directory file descriptor to scan
     *
     * @param fileList
     *     ArrayList of Strings to populate with FileBlock paths.
     */
    private void scanSubDirectory(File directory, List<String> fileList) {
        for (File file : directory.listFiles()) {
            if (file.isDirectory()) {
                scanSubDirectory(file, fileList);
                continue;
            }

            String fileName = file.getAbsolutePath();
            if (fileName.endsWith(FileSystem.BLOCK_EXTENSION)) {
                fileList.add(fileName);
            }
        }
    }

    /**
     * Does a full recovery from disk; this scans every block in the system,
     * reads metadata, and performs a checksum to verify integrity.  If not
     * already obvious, this could be very slow.
     */
    protected void fullRecovery() {
        logger.warning("Performing full recovery from disk");
        List<String> blockPaths = rebuildPaths(storageDirectory);
        recover(blockPaths);
    }

    /**
     * Scans the directory structure on disk to find all the blocks stored.
     *
     * @param storageDir the root directory to start scanning from.
     */
    protected List<String> rebuildPaths(File storageDir) {
        PerformanceTimer rebuildTimer = new PerformanceTimer();
        rebuildTimer.start();
        logger.info("Recovering path index");
        List<String> blockPaths = scanDirectory(storageDir);
        rebuildTimer.stop();
        logger.info("Index recovery took "
                + rebuildTimer.getLastResult() + " ms.");
        return blockPaths;
    }

    /**
     * Does a full recovery from disk on a particular Galileo partition; this
     * scans every block in the partition, reads its metadata, and performs a
     * checksum to verify block integrity.
     */
    protected void recover(List<String> blockPaths) {
        PerformanceTimer recoveryTimer = new PerformanceTimer();
        recoveryTimer.start();
        logger.info("Recovering metadata and building graph");
        long counter = 0;
        for (String path : blockPaths) {
            try {
                Metadata metadata = loadMetadata(path);
                storeMetadata(metadata, path);
                ++counter;
                if (counter % 10000 == 0) {
                    logger.info(String.format("%d blocks scanned, " +
                                "recovery %.2f%% complete.", counter,
                                ((float) counter / blockPaths.size()) * 100));
                }
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to recover metadata " +
                        "for block: " + path, e);
            }
        }
        recoveryTimer.stop();
        logger.info("Recovery operation complete. Time: "
                + recoveryTimer.getLastResult() + " ms.");
    }

    @Override
    public Block loadBlock(String blockPath)
    throws IOException, SerializationException {
        Block block = Serializer.restore(Block.class, blockPath);
        return block;
    }

    @Override
    public Metadata loadMetadata(String blockPath)
    throws IOException, SerializationException {
        /* We can just load the block as usual, but only perform the
         * deserialization on the Metadata.  Metadata is stored as the first
         * item in a serialized Block instance. */
        Metadata meta = Serializer.restore(Metadata.class, blockPath);
        return meta;
    }

    @Override
    public String storeBlock(Block block)
    throws FileSystemException, IOException {
        String name = block.getMetadata().getName();
        if (name.equals("")) {
            UUID blockUUID = UUID.nameUUIDFromBytes(block.getData());
            name = blockUUID.toString();
        }

        String blockPath = storageDirectory + "/" + name
            + FileSystem.BLOCK_EXTENSION;

        FileOutputStream blockOutStream = new FileOutputStream(blockPath);
        byte[] blockData = Serializer.serialize(block);
        blockOutStream.write(blockData);
        blockOutStream.close();

        return blockPath;
    }

    @Override
    public abstract void storeMetadata(Metadata metadata, String blockPath)
        throws FileSystemException, IOException;

    /**
     * Reports whether the Galileo filesystem is read-only.
     *
     * @return true if the filesystem is read-only.
     */
    public boolean isReadOnly() {
        return readOnly;
    }

    /**
     * Reports the amount of free space (in bytes) in the root storage
     * directory.
     *
     * @return long integer with the amount of free space, in bytes.
     */
    public long getFreeSpace() {
        return storageDirectory.getFreeSpace();
    }

    /**
     * Performs a clean shutdown of the FileSystem instance.  This includes
     * flushing buffers, writing changes out to disk, persisting index
     * structures, etc.
     * <p>
     * Note that this method may be called during a signal handling operation,
     * which may mean that the logging subsystem has already shut down, so
     * critical errors/information should be printed to stdout or stderr.
     * Furthermore, there is no guarantee all the shutdown operations will be
     * executed, so time is of the essence here.
     */
    public abstract void shutdown();
}
