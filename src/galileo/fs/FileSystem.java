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
import java.io.IOException;

import java.util.ArrayList;

import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

import galileo.dataset.BlockMetadata;
import galileo.dataset.FileBlock;
import galileo.dataset.MetaArray;
import galileo.graph.LogicalGraph;
import galileo.graph.LogicalGraphNode;
import galileo.graph.PhysicalGraph;

import galileo.serialization.SerializationException;
import galileo.serialization.Serializer;

public class FileSystem {

    private static final Logger logger = Logger.getLogger("galileo");

    public static final String METADATA_EXTENSION = ".gmeta";
    public static final String BLOCK_EXTENSION    = ".gblock";

    private File storageDirectory;

    private Journal journal;

    /** In-memory representation of the Galileo filesystem. */
    private LogicalGraph logicalGraph;

    /** On-disk representation of the Galileo filesystem.   */
    private PhysicalGraph physicalGraph;

    public FileSystem(String storageRoot) throws FileSystemException {
        System.out.println("Initializing Galileo File System.");
        System.out.println("Storage directory: " + storageRoot);

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

        System.out.println("Free space:        " +
            storageDirectory.getFreeSpace());

        //TODO: inode count

        /* Verify permissions. */
        boolean read, write, execute;
        read    = storageDirectory.canRead();
        write   = storageDirectory.canWrite();
        execute = storageDirectory.canExecute();

        System.out.println("File system permissions: " +
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

        boolean readOnly = false;
        if (!write) {
            System.out.println("Warning: storage directory is read-only.");
            readOnly = true;
        }

        try {
            journal = Journal.getInstance();
            journal.setJournalPath(storageRoot + "/journal");
        } catch (IOException e) {
            System.out.println("Could not initialize the journal!");
            e.printStackTrace();
        }

        logicalGraph  = new LogicalGraph();
        physicalGraph = new PhysicalGraph(storageDirectory, readOnly);
    }

    public void recoverMetadata() {
//        logicalGraph.recoverFromJournal();

        recover();
    }

    /**
     * Does a full recovery from disk; this scans every block in the system,
     * reads its metadata, and performs a checksum to verify block integrity.
     */
    private void recover() {
        logger.info("Recovering graph from disk");
        long recoverStart, recoverEnd, indexStart, indexEnd, metaStart, metaEnd;

        recoverStart = System.nanoTime();

        logger.info("Recovering index");
        indexStart = System.nanoTime();
        ArrayList<String> blockPaths = physicalGraph.getBlockPaths();
        indexEnd = System.nanoTime();
        logger.info("Index recovery complete.  Took " +
                (indexEnd - indexStart) * 1E-6 + " ms.");

        metaStart = System.nanoTime();
        logger.info("Recovering metadata and building graph");
        long counter = 0;
        for (String path : blockPaths) {
            try {
                BlockMetadata metadata = physicalGraph.loadMetadata(path);
                logicalGraph.addBlock(metadata, path);
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
        metaEnd = System.nanoTime();
        logger.info("Metadata recovery complete.  Recovered " + counter +
                " blocks in " + (metaEnd - metaStart) * 1E-6 + " ms.");

        recoverEnd = System.nanoTime();
        logger.info("Graph recovery complete.  Took "
            + (recoverEnd - recoverStart) * 1E-6 + " ms.");
    }

    public void storeBlock(byte[] blockBytes)
    throws IOException, FileSystemException, SerializationException {
        FileBlock block = Serializer.deserialize(FileBlock.class, blockBytes);
        storeBlock(block, blockBytes);
    }

    public void storeBlock(FileBlock block)
    throws IOException, FileSystemException {
        byte[] blockBytes = Serializer.serialize(block);
        storeBlock(block, blockBytes);
    }

    public void storeBlock(FileBlock block, byte[] blockBytes)
    throws IOException, FileSystemException {
        String blockPath = physicalGraph.storeBlock(block, blockBytes);
        logicalGraph.addBlock(block.getMetadata(), blockPath);
    }

    public MetaArray query(String query)
    throws IOException {
        System.out.println(" -> Processing query: " + query);
        LogicalGraphNode[] nodes = logicalGraph.query(query);

        ArrayList<String> nodePaths = new ArrayList<String>();
        for (LogicalGraphNode node : nodes) {
            nodePaths.addAll(node.getBlockPaths());
        }

        MetaArray metas = new MetaArray();
        for (String path : nodePaths) {
            try {
                BlockMetadata meta = physicalGraph.loadMetadata(path);
                meta.getRuntimeMetadata().setPhysicalGraphPath(path);
                metas.add(meta);
            } catch (Exception e) {
                System.out.println("Couldn't recover Metadata: " + path);
                e.printStackTrace();
                continue;
            }
        }

        System.out.println("huh");
        return metas;
    }

    /**
     * Reports whether the Galileo filesystem is read-only.
     *
     * @return <code>true</code> if the filesystem is read-only.
     */
    public boolean isReadOnly() {
        return physicalGraph.isReadOnly();
    }
}
