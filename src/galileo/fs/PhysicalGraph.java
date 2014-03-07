/*
Copyright (c) 2014, Colorado State University
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

import java.io.IOException;

import galileo.dataset.Block;
import galileo.dataset.Metadata;
import galileo.serialization.SerializationException;

public interface PhysicalGraph {

    /**
     * Retrieves a {@link Block} instance, given its path on disk.
     *
     * @param blockPath the physical location of the Block to load.
     *
     * @return Block instance stored at blockPath.
     */
    public Block loadBlock(String blockPath)
        throws IOException, SerializationException;

    /**
     * Retrieves a {@link Metadata} instance, given a {@link Block} path on
     * disk.
     *
     * @param blockPath the physical location of the Block to load metadata
     * from.
     *
     * @return Metadata stored in the Block specified by blockPath.
     */
    public Metadata loadMetadata(String blockPath)
        throws IOException, SerializationException;

    /**
     * Stores a {@link Block} on disk.
     *
     * @param block the Block instance to persist to disk.
     *
     * @return String representation of the Block path on disk.
     */
    public String storeBlock(Block block)
        throws IOException;
}
