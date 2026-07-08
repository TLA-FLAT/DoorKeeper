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
package nl.mpi.tla.flat.deposit.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import net.sf.saxon.s9api.XdmItem;
import net.sf.saxon.s9api.XdmNode;
import nl.mpi.tla.flat.deposit.Context;
import nl.mpi.tla.flat.deposit.DepositException;
import nl.mpi.tla.flat.deposit.sip.Collection;
import nl.mpi.tla.flat.deposit.sip.Resource;
import nl.mpi.tla.flat.deposit.sip.SIPInterface;
import nl.mpi.tla.flat.deposit.util.Global;
import nl.mpi.tla.util.Saxon;
import org.apache.commons.configuration.XMLConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Syncs the deposited SIP to Drupal: upserts an islandora_object node for the
 * SIP (linked to its parent collection nodes via field_member_of) and, for
 * each inserted/updated resource, a file entity (fedora:// flysystem URI) plus
 * a media entity attached to the node via field_media_of.
 *
 * Mementos and handles must exist by now, so this action runs after
 * EPICHandleUpdate, i.e. after the Fedora transaction has been committed.
 * All writes are upserts keyed on field_pid (nodes), uri (files) and
 * field_media_of+name (media), so the action is safe to re-run.
 *
 * @author menzowi
 * @author pavsri
 */
public class DrupalSync extends FedoraAction {

    private static final Logger logger = LoggerFactory.getLogger(DrupalSync.class.getName());

    protected static final ObjectMapper MAPPER = new ObjectMapper();

    protected HttpClient http;
    protected String server;
    protected String authorization;

    protected String nodeBundle = "islandora_object";
    protected String modelVocabulary = "islandora_models";
    protected String mediaUseVocabulary = "islandora_media_use";
    protected String sipModel = "Compound Object";
    protected String collectionModel = "Collection";
    protected final Set<String> collectionProfiles = new LinkedHashSet<>();

    protected final Map<String,TermRef> termCache = new HashMap<>();

    /**
     * One row of the mimetype mapping CSV. The columns follow the historic
     * format (mimetype;filetype;islandora_model;mediatype;media_use;
     * media_bundle_target_id;relation); filetype and media_bundle_target_id
     * are legacy columns and ignored.
     */
    public record MimeMapping(String model, String mediaBundle, String mediaUse, String sourceField) {
    }

    /**
     * A taxonomy term as needed by both APIs: REST references use the
     * internal tid, JSON:API relationships use the UUID.
     */
    public record TermRef(String tid, String uuid) {
    }

    /**
     * A Drupal node as needed by both APIs: REST references (field_media_of)
     * use the internal nid, JSON:API relationships and URLs use the UUID.
     */
    public record NodeRef(String nid, String uuid) {
    }

    @Override
    public boolean perform(Context context) throws DepositException {
        try {
            connect(context);

            loadDrupalConfig();
            Map<String,MimeMapping> mimeMap = loadMimeMap();

            SIPInterface sip = context.getSIP();
            if (!sip.hasPID() || !sip.hasFID()) {
                logger.warn("SIP has no PID and/or FID; nothing to sync to Drupal");
                return true;
            }

            // parent collection nodes have to exist before the SIP node can be a member of them
            List<NodeRef> parents = new ArrayList<>();
            for (Collection col : sip.getCollections(false)) {
                if (col.hasPID() && col.hasFID()) {
                    parents.add(upsertNode(context, col.getPID(), col.getFID(true), collectionModel, new ArrayList<>(), null));
                } else {
                    logger.warn("Parent collection["+col+"] has no PID and/or FID; SIP node won't be a member of it");
                }
            }

            String model = isCollectionSIP(sip) ? collectionModel : sipModel;
            NodeRef node = upsertNode(context, sip.getPID(), sip.getFID(true), model, parents, null);

            for (Resource res : sip.getResources()) {
                if (!(res.isInsert() || res.isUpdate()))
                    continue;
                if (!res.hasPID() || !res.hasFID()) {
                    logger.warn("Resource["+res+"] has no PID and/or FID; skipped");
                    continue;
                }
                // Each resource is its own archival object, so it gets its own
                // islandora_object node — titled with its filename, modelled
                // from its mimetype, and a member of the compound SIP node.
                // The media is attached to that child node, not the compound:
                // a compound node carries only the bundle-level CMD/DC/OLAC
                // metadata and never media of its own.
                String mime = (res.hasMime() ? res.getMime() : "application/octet-stream");
                MimeMapping mapping = lookupMapping(mimeMap, mime);
                String filename = (res.getFile() != null ? res.getFile().getName()
                        : res.getFID(true).toString().replaceAll(".*/",""));
                NodeRef child = upsertNode(context, res.getPID(), res.getFID(true),
                        mapping.model(), List.of(node), filename);
                syncResource(context, res, child, mimeMap);
            }
        } catch (DepositException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new DepositException("Syncing the deposit to Drupal failed!", ex);
        }
        return true;
    }

    /**
     * Create or update the islandora_object node for the object {@code fid}.
     *
     * Nodes are keyed on the Fedora object ID stored in field_pid: handles
     * are per-version (every update deposit mints a new one), so the FID is
     * the only identifier that keeps one node per archived object.
     *
     * Nodes are written through JSON:API: the core REST node POST route
     * (path /node) is shadowed by the frontpage view's route.
     */
    protected NodeRef upsertNode(Context context, URI pid, URI fid, String model, List<NodeRef> parents, String suppliedTitle) throws DepositException {
        String fidStr = fid.toString().replaceAll("#.*","");
        String title = (suppliedTitle == null || suppliedTitle.isBlank()) ? fetchTitle(fid, fidStr) : suppliedTitle;
        TermRef modelTerm = termId(modelVocabulary, model);

        ObjectNode attributes = MAPPER.createObjectNode();
        attributes.put("title", title);

        ObjectNode relationships = MAPPER.createObjectNode();
        relationships.set("field_model", MAPPER.createObjectNode()
                .set("data", MAPPER.createObjectNode()
                        .put("type", "taxonomy_term--"+modelVocabulary)
                        .put("id", modelTerm.uuid())));
        if (!parents.isEmpty()) {
            ArrayNode members = MAPPER.createArrayNode();
            for (NodeRef parent : parents)
                members.add(MAPPER.createObjectNode()
                        .put("type", "node--"+nodeBundle)
                        .put("id", parent.uuid()));
            relationships.set("field_member_of", MAPPER.createObjectNode().set("data", members));
        }

        ObjectNode data = MAPPER.createObjectNode();
        data.put("type", "node--"+nodeBundle);
        data.set("attributes", attributes);
        data.set("relationships", relationships);
        ObjectNode document = MAPPER.createObjectNode();
        document.set("data", data);

        JsonNode existing = jsonapiOne("node/"+nodeBundle, "filter[field_pid]="+encode(fidStr));
        if (existing != null) {
            String nid = existing.at("/attributes/drupal_internal__nid").asText();
            String uuid = existing.at("/id").asText();
            data.put("id", uuid);
            apiCall("PATCH", server+"/jsonapi/node/"+nodeBundle+"/"+uuid, document);
            logger.info("Updated Drupal node["+nid+"] for FID["+fidStr+"] PID["+pidToHdl(pid)+"]");
            return new NodeRef(nid, uuid);
        }

        attributes.put("field_pid", fidStr);
        JsonNode created = apiCall("POST", server+"/jsonapi/node/"+nodeBundle, document);
        String nid = created.at("/data/attributes/drupal_internal__nid").asText();
        String uuid = created.at("/data/id").asText();
        context.registerRollbackEvent(this, "drupal node creation", "nid", nid, "fid", fidStr);
        logger.info("Created Drupal node["+nid+"] for FID["+fidStr+"] PID["+pidToHdl(pid)+"] title["+title+"]");
        return new NodeRef(nid, uuid);
    }

    /**
     * Upsert the file entity and media entity for one resource.
     */
    protected void syncResource(Context context, Resource res, NodeRef node, Map<String,MimeMapping> mimeMap) throws DepositException {
        try {
            String pidStr = pidToHdl(res.getPID());
            String mime = (res.hasMime() ? res.getMime() : "application/octet-stream");
            MimeMapping mapping = lookupMapping(mimeMap, mime);

            String fid = res.getFID(true).toString();
            String frag = res.getFID().getRawFragment();
            String dsid = (frag != null ? frag.replaceAll("@.*","") : "OBJ");
            String fileUri = "fedora://"+fid+"/"+dsid;
            String filename = (res.getFile() != null ? res.getFile().getName() : fid.replaceAll(".*/",""));

            // file entity, keyed on its flysystem URI; created temporary
            // (status can't be set: core forbids it and flips it to permanent
            // once the media below references the file)
            String fileId;
            JsonNode existingFile = jsonapiOne("file/file", "filter[uri.value]="+encode(fileUri));
            if (existingFile != null) {
                fileId = existingFile.at("/attributes/drupal_internal__fid").asText();
                logger.debug("Found Drupal file["+fileId+"] for URI["+fileUri+"]");
            } else {
                ObjectNode file = MAPPER.createObjectNode();
                file.set("uri", values(fileUri));
                file.set("filename", values(filename));
                file.set("filemime", values(mime));
                JsonNode created = apiCall("POST", server+"/entity/file?_format=json", file);
                fileId = created.at("/fid/0/value").asText();
                context.registerRollbackEvent(this, "drupal file creation", "fid", fileId, "uri", fileUri);
                logger.info("Created Drupal file["+fileId+"] for URI["+fileUri+"]");
            }

            // media entity, keyed on the node it belongs to and the file name
            ObjectNode media = MAPPER.createObjectNode();
            media.set("bundle", targets("target_id", mapping.mediaBundle()));
            media.set("name", values(filename));
            media.set("field_media_of", targets("target_id", node.nid()));
            media.set("field_media_use", targets("target_id", termId(mediaUseVocabulary, mapping.mediaUse()).tid()));
            media.set(mapping.sourceField(), targets("target_id", fileId));

            JsonNode existingMedia = jsonapiOne("media/"+mapping.mediaBundle(),
                    "filter[field_media_of.id]="+encode(node.uuid())+"&filter[name]="+encode(filename));
            if (existingMedia != null) {
                String mid = existingMedia.at("/attributes/drupal_internal__mid").asText();
                apiCall("PATCH", server+"/media/"+mid+"?_format=json", media);
                logger.info("Updated Drupal media["+mid+"]["+mapping.mediaBundle()+"] for resource PID["+pidStr+"]");
            } else {
                JsonNode created = apiCall("POST", server+"/entity/media?_format=json", media);
                String mid = created.at("/mid/0/value").asText();
                context.registerRollbackEvent(this, "drupal media creation", "mid", mid, "pid", pidStr);
                logger.info("Created Drupal media["+mid+"]["+mapping.mediaBundle()+"] file["+fileId+"] node["+node.nid()+"] for resource PID["+pidStr+"]");
            }
        } catch (DepositException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new DepositException("Syncing resource["+res+"] to Drupal failed!", ex);
        }
    }

    /** Determine whether the SIP's CMDI profile is configured as a collection profile. */
    protected boolean isCollectionSIP(SIPInterface sip) {
        try {
            String profile = cmdiProfile(Saxon.wrapNode(sip.getRecord()));
            boolean collection = isCollectionProfile(profile);
            logger.debug("CMDI profile["+profile+"] model["+(collection ? collectionModel : sipModel)+"]");
            return collection;
        } catch (Exception ex) {
            logger.warn("Couldn't determine the SIP's CMDI profile; using model["+sipModel+"]", ex);
            return false;
        }
    }

    protected String cmdiProfile(XdmNode cmd) throws Exception {
        return Saxon.xpath2string(cmd,
                "normalize-space(/cmd:CMD/cmd:Header/cmd:MdProfile)", null, Global.NAMESPACES);
    }

    protected boolean isCollectionProfile(String profile) {
        return profile != null && collectionProfiles.contains(profile);
    }

    /**
     * The object's DC title from Fedora (written by cmd2fox at ingest, the
     * source of truth for node titles across all CMDI profiles); falls back
     * to the FID's local name.
     */
    protected String fetchTitle(URI fid, String fallback) {
        URI cleanFid;
        try {
            cleanFid = new URI(fid.toString().replaceAll("#.*",""));
            XdmNode dc = getXMLDataStream(cleanFid, "DC");
            String title = dc == null ? null
                    : Saxon.xpath2string(dc, "normalize-space((//dc:title)[1])", null, Global.NAMESPACES);
            if (title != null && !title.isEmpty())
                return title;

            XdmNode info = fcrepo(cleanFid);
            title = Saxon.xpath2string(info, "normalize-space((//dc:title)[1])", null, Global.NAMESPACES);
            if (title != null && !title.isEmpty())
                return title;
        } catch (Exception ex) {
            logger.warn("Couldn't fetch a title for ["+fid+"]; falling back to the FID", ex);
        }
        return fallback.replaceAll(".*/","");
    }

    /**
     * Look up a taxonomy term by name (cached per run).
     */
    protected TermRef termId(String vocabulary, String name) throws DepositException {
        String key = vocabulary+"|"+name;
        TermRef term = termCache.get(key);
        if (term == null) {
            JsonNode result = jsonapiOne("taxonomy_term/"+vocabulary, "filter[name]="+encode(name));
            if (result == null)
                throw new DepositException("Taxonomy term["+name+"] doesn't exist in vocabulary["+vocabulary+"]!");
            term = new TermRef(result.at("/attributes/drupal_internal__tid").asText(), result.at("/id").asText());
            termCache.put(key, term);
        }
        return term;
    }

    /**
     * First match wins: exact mimetype, then type wildcard (e.g. image/*), then *.
     */
    protected MimeMapping lookupMapping(Map<String,MimeMapping> mimeMap, String mime) {
        MimeMapping mapping = mimeMap.get(mime);
        if (mapping == null)
            mapping = mimeMap.get(mime.replaceAll("/.*","/*"));
        if (mapping == null)
            mapping = mimeMap.get("*");
        if (mapping == null) {
            logger.warn("No mimetype mapping for ["+mime+"] and no * fallback; using the generic file media type");
            mapping = new MimeMapping("Binary", "file", "Original File", "field_media_file");
        }
        return mapping;
    }

    protected Map<String,MimeMapping> loadMimeMap() throws DepositException {
        String path = getParameter("mimeMapping", getParameter("path", null));
        if (path == null || path.isEmpty())
            throw new DepositException("No mimetype mapping has been specified! Use the mimeMapping parameter.");
        Map<String,MimeMapping> map = new HashMap<>();
        try (BufferedReader br = new BufferedReader(new FileReader(new File(path)))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#"))
                    continue;
                String[] parts = line.split(";", -1);
                if (parts.length < 7) {
                    logger.warn("Skipping malformed mimetype mapping line["+line+"]");
                    continue;
                }
                String mime = parts[0].trim();
                if (map.containsKey(mime)) {
                    logger.warn("Duplicate mimetype mapping for ["+mime+"]; keeping the first one");
                    continue;
                }
                map.put(mime, new MimeMapping(parts[2].trim(), parts[3].trim(), parts[4].trim(), parts[6].trim()));
            }
        } catch (IOException ex) {
            throw new DepositException("Couldn't read the mimetype mapping["+path+"]!", ex);
        }
        if (map.isEmpty())
            throw new DepositException("The mimetype mapping["+path+"] is empty!");
        return map;
    }

    protected XMLConfiguration loadDrupalConfig() throws DepositException {
        try {
            String drupal = this.getParameter("drupalConfig");
            if (drupal == null)
                throw new DepositException("No Drupal configuration has been specified! Use the drupalConfig parameter.");
            File config = new File(drupal);
            if (!config.isFile() || !config.canRead())
                throw new DepositException("The Drupal configuration["+drupal+"] can't be read!");

            XMLConfiguration xConfig = new XMLConfiguration(config);
            server = xConfig.getString("server");
            if (server == null || server.isEmpty())
                throw new DepositException("The Drupal configuration["+drupal+"] doesn't specify a server!");
            server = server.replaceAll("/+$","");

            String user = xConfig.getString("userName");
            String pass = readSecret(xConfig, "userPass", "userPassFile");
            if (user == null || user.isEmpty() || pass == null || pass.isEmpty())
                throw new DepositException("The Drupal configuration["+drupal+"] doesn't specify userName/userPass!");
            authorization = "Basic " + Base64.getEncoder().encodeToString((user+":"+pass).getBytes(StandardCharsets.UTF_8));

            HttpClient.Builder builder = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1);
            if (xConfig.getBoolean("trustAll", false))
                throw new DepositException("Drupal trustAll is no longer supported; use internal HTTP or a trusted TLS certificate");
            http = builder.build();

            nodeBundle = xConfig.getString("nodeBundle", nodeBundle);
            modelVocabulary = xConfig.getString("modelVocabulary", modelVocabulary);
            mediaUseVocabulary = xConfig.getString("mediaUseVocabulary", mediaUseVocabulary);
            sipModel = xConfig.getString("sipModel", sipModel);
            collectionModel = xConfig.getString("collectionModel", collectionModel);
            collectionProfiles.clear();
            for (Object configured : xConfig.getList("collectionProfile")) {
                for (String profile : configured.toString().split(",")) {
                    if (!profile.isBlank())
                        collectionProfiles.add(profile.trim());
                }
            }
            return xConfig;
        } catch (DepositException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new DepositException("Loading the Drupal configuration failed!", ex);
        }
    }

    /**
     * JSON:API collection GET returning the first matching resource, or null.
     */
    protected JsonNode jsonapiOne(String resource, String query) throws DepositException {
        JsonNode result = apiCall("GET", server+"/jsonapi/"+resource+"?"+query, null);
        JsonNode data = result.get("data");
        if (data == null || !data.isArray() || data.isEmpty())
            return null;
        if (data.size() > 1)
            logger.warn("Multiple Drupal ["+resource+"] matches for ["+query+"]; using the first one");
        return data.get(0);
    }

    protected JsonNode apiCall(String method, String url, ObjectNode body) throws DepositException {
        try {
            HttpRequest.Builder req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", authorization);
            if (body != null)
                req = req.header("Content-Type", url.contains("/jsonapi/") ? "application/vnd.api+json" : "application/json")
                         .method(method, HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
            else
                req = req.method(method, HttpRequest.BodyPublishers.noBody());

            HttpResponse<String> response = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
            logger.debug("Drupal "+method+"["+url+"] code["+response.statusCode()+"]");
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new DepositException("Unexpected status["+response.statusCode()+"] from Drupal "+method+"["+url+"]: "
                        + excerpt(response.body()));
            return (response.body()==null || response.body().isEmpty() ? MAPPER.createObjectNode() : MAPPER.readTree(response.body()));
        } catch (DepositException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new DepositException("Drupal "+method+"["+url+"] failed!", ex);
        }
    }

    @Override
    public void rollback(Context context, List<XdmItem> events) {
        if (events.isEmpty())
            return;
        try {
            loadDrupalConfig();
        } catch (DepositException ex) {
            logger.error("rollback action["+this.getName()+"] couldn't load the Drupal configuration!", ex);
            return;
        }
        // reverse order: media before files before nodes
        for (ListIterator<XdmItem> iter = events.listIterator(events.size()); iter.hasPrevious();) {
            XdmItem event = iter.previous();
            try {
                String tpe = Saxon.xpath2string(event, "@type");
                String path = null;
                if (tpe.equals("drupal media creation"))
                    path = "/media/"+Saxon.xpath2string(event, "param[@name='mid']/@value");
                else if (tpe.equals("drupal file creation"))
                    path = "/entity/file/"+Saxon.xpath2string(event, "param[@name='fid']/@value");
                else if (tpe.equals("drupal node creation"))
                    path = "/node/"+Saxon.xpath2string(event, "param[@name='nid']/@value");
                else {
                    logger.error("rollback action["+this.getName()+"] unknown event["+tpe+"]!");
                    continue;
                }
                apiCall("DELETE", server+path+"?_format=json", null);
                logger.debug("rollback action["+this.getName()+"] event["+tpe+"] deleted ["+path+"]");
            } catch (Exception ex) {
                logger.error("rollback action["+this.getName()+"] event["+event+"] failed!", ex);
            }
        }
    }

    /**
     * Normalize a PID to the hdl:prefix/uuid form used in field_pid.
     */
    protected static String pidToHdl(URI pid) {
        return pid.toString().replaceAll("^http(s?)://hdl.handle.net/","hdl:");
    }

    protected static ArrayNode values(String value) {
        return MAPPER.createArrayNode().add(MAPPER.createObjectNode().put("value", value));
    }

    protected static ArrayNode targets(String key, String id) {
        return MAPPER.createArrayNode().add(MAPPER.createObjectNode().put(key, id));
    }

    protected static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    protected static String excerpt(String body) {
        if (body == null)
            return "";
        return (body.length() > 500 ? body.substring(0, 500)+"..." : body);
    }
}
