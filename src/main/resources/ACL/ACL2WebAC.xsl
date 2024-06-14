<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:xs="http://www.w3.org/2001/XMLSchema"
    xmlns="urn:oasis:names:tc:xacml:1.0:policy"
    exclude-result-prefixes="xs"
    version="3.0">
    
    <xsl:variable name="NL" select="system-property('line.separator')"/>
    <xsl:variable name="TAB" select="'  '"/>
    
    <xsl:param name="acl-base" select="'.'"/>
    <xsl:param name="roles" select="()"/>
    
    <xsl:variable name="debug" select="false()" static="yes"/>
    
    <xsl:variable name="dsid-functions" select="(
        'id-getDatastreamDissemination'
    )"/>
    
    <xsl:variable name="access-functions" select="(
        'api-a',
        'id-getDatastreamHistory',
        'id-listObjectInResourceIndexResults'
    )"/>
    
    <xsl:variable name="management-functions" select="(
        'id-addDatastream',
        'id-addDisseminator',
        'id-adminPing',
        'id-getDisseminatorHistory',
        'id-getNextPid',
        'id-ingest',
        'id-modifyDatastreamByReference',
        'id-modifyDatastreamByValue',
        'id-modifyDisseminator',
        'id-modifyObject',
        'id-purgeObject',
        'id-purgeDatastream',
        'id-purgeDisseminator',
        'id-setDatastreamState',
        'id-setDisseminatorState',
        'id-setDatastreamVersionable',
        'id-compareDatastreamChecksum',
        'id-serverShutdown',
        'id-serverStatus',
        'id-upload',
        'id-dsstate',
        'id-resolveDatastream',
        'id-reloadPolicies'
    )"/>

    <xsl:template match="text()" mode="#all"/>
    
    <xsl:template match="(read|write)[exists(user)]" mode="user">
        <xsl:text expand-text="yes">  acl:agent</xsl:text>
        <xsl:value-of select="$NL"/>
        <xsl:for-each select="user">
            <xsl:variable name="user" select="."/>
            <xsl:message>INF: <xsl:value-of select="local-name(..)"/> access for user[<xsl:value-of select="$user"/>]!</xsl:message>
            <xsl:text expand-text="yes">    "{$user}"</xsl:text>
            <xsl:choose>
                <xsl:when test="position() lt last()">
                    <xsl:text>,</xsl:text>
                </xsl:when>
                <xsl:otherwise>
                    <xsl:text>;</xsl:text>
                </xsl:otherwise>
            </xsl:choose>
            <xsl:value-of select="$NL"/>
        </xsl:for-each>
    </xsl:template>

    <xsl:template match="(read|write)[exists(role)]" mode="role">
        <xsl:variable name="mode" select="."/>
        <xsl:variable name="all" as="xs:string*">
            <xsl:for-each select="role">
                <xsl:variable name="role" select="."/>
                <xsl:sequence select="$role"/>
                <xsl:if test="exists($roles)">
                    <xsl:sequence select="$roles//role[.=$role]/following-sibling::role"/>
                </xsl:if>
            </xsl:for-each>
        </xsl:variable>
        <xsl:text expand-text="yes">  acl:agentGroup</xsl:text>
        <xsl:value-of select="$NL"/>
        <xsl:for-each select="distinct-values($all)">
            <xsl:variable name="role" select="."/>
            <xsl:message>INF: <xsl:value-of select="local-name($mode)"/> access for any[<xsl:value-of select="$role"/>]!</xsl:message>
            <xsl:text expand-text="yes">    "{$role}"</xsl:text>
            <xsl:choose>
                <xsl:when test="position() lt last()">
                    <xsl:text>,</xsl:text>
                </xsl:when>
                <xsl:otherwise>
                    <xsl:text>;</xsl:text>
                </xsl:otherwise>
            </xsl:choose>
            <xsl:value-of select="$NL"/>
        </xsl:for-each>
    </xsl:template>
    
    <xsl:template name="WebAC">
        <xsl:param name="dsid" select="'OBJ'"/>
        <xsl:param name="visible" select="true()"/>
        <xsl:text expand-text="yes">@prefix acl: &lt;http://www.w3.org/ns/auth/acl#>.</xsl:text>
        <xsl:value-of select="$NL"/>
        <xsl:value-of select="$NL"/>
        <!-- READ -->
        <xsl:text expand-text="yes">&lt;#authzR> a acl:Authorization;</xsl:text>
        <xsl:value-of select="$NL"/>
        <xsl:text expand-text="yes">  acl:accessTo &lt;{@id}>;</xsl:text>
        <xsl:value-of select="$NL"/>
        <xsl:apply-templates select="read" mode="user"/>
        <xsl:apply-templates select="read" mode="role"/>
        <xsl:text expand-text="yes">  acl:mode acl:Read.</xsl:text>
        <xsl:value-of select="$NL"/>
        <xsl:value-of select="$NL"/>
        <!-- WRITE -->
        <xsl:text expand-text="yes">&lt;#authzW> a acl:Authorization;</xsl:text>
        <xsl:value-of select="$NL"/>
        <xsl:text expand-text="yes">  acl:accessTo &lt;{@id}>;</xsl:text>
        <xsl:value-of select="$NL"/>
        <xsl:apply-templates select="write" mode="user"/>
        <xsl:apply-templates select="write" mode="role"/>
        <xsl:text>  acl:mode acl:Write.</xsl:text>
        <xsl:value-of select="$NL"/>
        <xsl:value-of select="$NL"/>
    </xsl:template>

    <xsl:template name="rels-ext">
        <xsl:param name="visible" select="true()"/>
        <rdf:RDF xmlns:fedora="info:fedora/fedora-system:def/relations-external#" xmlns:fedora-model="info:fedora/fedora-system:def/model#" xmlns:islandora="http://islandora.ca/ontology/relsext#" xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
            <rdf:Description rdf:about="info:fedora/{@id}">
                <xsl:if test="not($visible)">
                    <!-- read -->
                    <xsl:for-each select="read/user">
                        <xsl:variable name="user" select="."/>
                        <islandora:isViewableByUser>
                            <xsl:value-of select="$user"/>
                        </islandora:isViewableByUser>
                    </xsl:for-each>
                    <xsl:variable name="all" as="xs:string*">
                        <xsl:for-each select="read/role">
                            <xsl:variable name="role" select="."/>
                            <xsl:sequence select="$role"/>
                            <xsl:if test="exists($roles)">
                                <xsl:sequence select="$roles//role[.=$role]/following-sibling::role"/>
                            </xsl:if>
                        </xsl:for-each>
                    </xsl:variable>
                    <xsl:for-each select="distinct-values($all)">
                        <xsl:variable name="role" select="."/>
                        <islandora:isViewableByRole>
                            <xsl:value-of select="$role"/>
                        </islandora:isViewableByRole>
                    </xsl:for-each>
                </xsl:if>
                <!-- write -->
                <xsl:for-each select="write/user">
                    <xsl:variable name="user" select="."/>
                    <islandora:isManageableByUser>
                        <xsl:value-of select="$user"/>
                    </islandora:isManageableByUser>
                </xsl:for-each>
                <xsl:for-each select="write/role">
                    <xsl:variable name="role" select="."/>
                    <islandora:isManageableByRole>
                        <xsl:value-of select="$role"/>
                    </islandora:isManageableByRole>
                </xsl:for-each>
            </rdf:Description>
        </rdf:RDF>
    </xsl:template>
        
    <xsl:template match="sip">
        <xsl:variable name="href-base" select="concat($acl-base,'/',replace(@id, '[^a-zA-Z0-9]', '_'))"/>
        <xsl:variable name="visible" select="count(read/*) eq 1 and read/role='anonymous user'"/>
        
        <xsl:message>INF: SIP[<xsl:value-of select="@pid"/>]</xsl:message>
        <xsl:message>DBG: SIP policy[<xsl:value-of select="$href-base"/>.nt]</xsl:message>
        <xsl:result-document href="{$href-base}.nt" method="text" encoding="UTF-8">
            <xsl:call-template name="WebAC">
                <xsl:with-param name="dsid" select="'CMD'"/>
                <xsl:with-param name="visible" select="$visible"/>
            </xsl:call-template>
        </xsl:result-document>
        
        <xsl:message>DBG: SIP RELS-EXT[<xsl:value-of select="$href-base"/>.RELS-EXT.nt]</xsl:message>
        <xsl:result-document href="{$href-base}.RELS-EXT.nt" method="text" encoding="UTF-8">
            <xsl:call-template name="rels-ext">
                <xsl:with-param name="visible" select="$visible"/>
            </xsl:call-template>
        </xsl:result-document>
        
        <xsl:apply-templates select="resource">
            <xsl:with-param name="visible" select="$visible"/>
        </xsl:apply-templates>
    </xsl:template>
    
    <xsl:template match="resource">
        <xsl:param name="visible" select="true()"/>
        <xsl:variable name="href-base" select="concat($acl-base,'/',replace(@id, '[^a-zA-Z0-9]', '_'))"/>
        
        <xsl:message>INF: resource[<xsl:value-of select="@pid"/>]</xsl:message>
        <xsl:message>DBG: resource policy[<xsl:value-of select="$href-base"/>.xml]</xsl:message>
        <xsl:result-document href="{$href-base}.nt" method="text" encoding="UTF-8">
            <xsl:call-template name="WebAC">
                <xsl:with-param name="dsid" select="'OBJ'"/>
                <xsl:with-param name="visible" select="$visible"/>
            </xsl:call-template>
        </xsl:result-document>
        
        <xsl:message>DBG: resource RELS-EXT[<xsl:value-of select="$href-base"/>,RELS-EXT.nt]</xsl:message>
        <xsl:result-document href="{$href-base}.RELS-EXT.nt" method="text" encoding="UTF-8">
            <xsl:call-template name="rels-ext">
                <xsl:with-param name="visible" select="$visible"/>
            </xsl:call-template>
        </xsl:result-document>        
    </xsl:template>
    
</xsl:stylesheet>