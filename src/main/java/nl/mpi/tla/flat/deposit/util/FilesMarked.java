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

import java.util.Arrays;

import org.apache.commons.codec.digest.DigestUtils;

/**
 * This util class keeps track of files marked for encryption
 * using an md5 hash of the file name.
 */
public class FilesMarked {

    private String[] marked = {};
    private String token = "";

    public String[] getMarked() {
        return this.marked;
    }

    public void setMarked(String[] marked) {
        this.marked = marked;
    }

    public boolean isMarked(String filename) {

        String hex = this.generateMd5(filename);
        return Arrays.asList(this.marked).contains(hex);
    }

    public String getToken()  {
        return this.token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    private String generateMd5(String filename) {
        return DigestUtils.md5Hex(filename);
    }
}