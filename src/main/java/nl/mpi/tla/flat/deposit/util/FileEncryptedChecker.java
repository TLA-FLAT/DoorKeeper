/*
 * Copyright (C) 2015-2017 The Language Archive
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
package nl.mpi.tla.flat.deposit.util;

import java.net.URI;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;

import net.sf.saxon.om.Sequence;
import net.sf.saxon.value.StringValue;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * This util class will allow someone to check whether a file provided inside the cmdi
 * is encrypted or not. @see SaxonExtensionFunctions.java
 */
class FileEncryptedChecker {

  /**
   * Inside de cmd2fox.xsl file the SaxExtensionFunction sx:generateAltIds
   * will call this method to mark the file as encrypted or not in the cmdi.
   *
   * @param arguments
   * @return
   * @throws Exception
   */
  public static boolean isMarked(Sequence[] arguments) throws Exception {

    Sequence recordCmdiFile = arguments[0];
    String filename = ((StringValue) arguments[1].head()).getStringValue();

    return getFilesMarkedForEncryption(recordCmdiFile).isMarked(filename);
  }

  /**
   * This method will return a list of files marked for encryption taken from flat_encryption.json.
   * This file is generated in drupal, in de Bundle.php file inside flat_deposit/Helpers/IngestService
   *
   * @param recordCmdiFile
   * @return
   * @throws Exception
   */
  private static FilesMarked getFilesMarkedForEncryption(Sequence recordCmdiFile) throws Exception {

    URI uri = new URI(((StringValue) recordCmdiFile.head()).getStringValue());
    Path flatEncryptionFile = Paths.get(uri).resolveSibling("flat_encryption.json");

    return parseFlatEncryptionFile(flatEncryptionFile);
  }

  /**
   * Parse the flat_encryption.json file and return a FilesMarked object.
   *
   * @param flatEncryptionFile
   * @return
   * @throws Exception
   */
  private static FilesMarked parseFlatEncryptionFile(Path flatEncryptionFile) throws Exception {

      byte[] encoded;

      if (flatEncryptionFile.toFile().exists()) {

          encoded = Files.readAllBytes(flatEncryptionFile);

      } else {

          String raw = "{\"marked\": [], \"token\": \"\"}";
          encoded = raw.getBytes();
      }

      String json = new String(encoded, StandardCharsets.UTF_8);

      ObjectMapper objectMapper = new ObjectMapper();
      return objectMapper.readValue(json, FilesMarked.class);
  }
}
