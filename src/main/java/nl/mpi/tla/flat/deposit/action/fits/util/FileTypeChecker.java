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
package nl.mpi.tla.flat.deposit.action.fits.util;

import java.io.File;
import java.util.List;

import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.sf.saxon.s9api.SaxonApiException;
import nl.mpi.tla.flat.deposit.DepositException;

/**
 * Class that has access to the list of accepted file types.
 * @author guisil
 */
public class FileTypeChecker {
	
	private static final Logger logger = LoggerFactory.getLogger(FileTypeChecker.class);

	private List<String> acceptedMimetypes;
	
	//to be used only by the factory method
	private FileTypeChecker(MimetypesLoader mimetypesLoader, Source mimetypesSource) throws SaxonApiException, DepositException {
		acceptedMimetypes = mimetypesLoader.loadMimetypes(mimetypesSource);
	}
	
	/**
	 * Factory method
	 */
	public static FileTypeChecker getNewFileTypeChecker(File mimetypesFile) {
		try {
			return new FileTypeChecker(MimetypesLoader.getNewMimetypesLoader(), new StreamSource(mimetypesFile));
		} catch (SaxonApiException | DepositException e) {
			//TODO Handle exception
			throw new UnsupportedOperationException("not handled yet");
		}
	}
	
	/**
	 * @param mimetype Mimetype to check
	 * @return true if given mimetype is acceptable
	 */
	public boolean isMimetypeInAcceptableList(String mimetype) {
		
		logger.debug("Checking if mimetype {} is acceptable", mimetype);
		
		if(acceptedMimetypes.contains(mimetype)) {
			logger.debug("Mimetype {} included in the acceptable list", mimetype);
			return true;
		}
		logger.debug("Mimetype {} not included in the acceptable list", mimetype);
		return false;
	}
}
