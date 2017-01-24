package nl.mpi.tla.flat.deposit.action.mapping.util;

import nl.mpi.tla.flat.deposit.sip.Resource;

import java.io.IOException;
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

            if(Paths.get(cmdiResource.getURI()).toString().equals( file.toRealPath().toString()))

            matchingResources.add(file);
        }


        return FileVisitResult.CONTINUE;
    }

/*
*/


}
