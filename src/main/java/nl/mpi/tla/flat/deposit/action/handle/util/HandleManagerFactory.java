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
package nl.mpi.tla.flat.deposit.action.handle.util;

import java.io.FileNotFoundException;
import java.io.IOException;

import nl.mpi.handle.util.HandleManager;
import nl.mpi.handle.util.implementation.HandleInfoProviderImpl;
import nl.mpi.handle.util.implementation.HandleManagerImpl;
import nl.mpi.handle.util.implementation.HandleParserImpl;
import nl.mpi.handle.util.implementation.HandleUtil;

public class HandleManagerFactory {

	//avoid instantiation of the class
	private HandleManagerFactory() {
		throw new AssertionError();
	}
	
	public static HandleManager getNewHandleManager(
			String handlePrefix, String handleAdminKeyFilePath, String handleAdminUserHandleIndex,
			String handleAdminUserHandle, String handleAdminPassword)
					throws FileNotFoundException, IOException {
		return new HandleManagerImpl(
				new HandleInfoProviderImpl(handlePrefix),
				new HandleParserImpl(handlePrefix),
				new HandleUtil(handleAdminKeyFilePath, handleAdminUserHandleIndex, handleAdminUserHandle, handleAdminPassword),
				handlePrefix);
	}
}
