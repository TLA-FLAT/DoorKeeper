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
package nl.mpi.tla.flat.deposit.action.mapping.util;

import nl.mpi.tla.flat.deposit.sip.Resource;

import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Created by danrhe on 24/01/17.
 */
public class FileWalker extends SimpleFileVisitor<Path> {


    public Set<Resource> cmdiResources = new LinkedHashSet<>();
    public ArrayList<Path> foundResources = new ArrayList<>();
    public ArrayList<Path> matchingResources = new ArrayList<>();

    public FileWalker(Set<Resource> res){

        cmdiResources = res;

    }


    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
            throws IOException {

        if (attrs.isRegularFile()){
            foundResources.add(file);
        }

        for (Resource cmdiResource: cmdiResources) {
            URI uri = cmdiResource.getURI();
            if (uri.getScheme().equals("file")) {
                if (Paths.get(cmdiResource.getURI()).toString().equals(file.toRealPath().toString()))
                    matchingResources.add(file);
            }
        }


        return FileVisitResult.CONTINUE;
    }

/*
*/


}
