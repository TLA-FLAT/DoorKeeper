<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:xs="http://www.w3.org/2001/XMLSchema"
    xmlns="urn:oasis:names:tc:xacml:1.0:policy"
    exclude-result-prefixes="xs"
    version="3.0">
    
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
    
    <xsl:template match="read|write" mode="user">
        <Apply FunctionId="urn:oasis:names:tc:xacml:1.0:function:string-at-least-one-member-of">
            <SubjectAttributeDesignator DataType="http://www.w3.org/2001/XMLSchema#string" MustBePresent="false" AttributeId="urn:fedora:names:fedora:2.1:subject:loginId"/>
            <Apply FunctionId="urn:oasis:names:tc:xacml:1.0:function:string-bag">
                <xsl:for-each select="user">
                    <xsl:variable name="user" select="."/>
                    <xsl:message>INF: <xsl:value-of select="local-name(..)"/> access for user[<xsl:value-of select="$user"/>]!</xsl:message>
                    <AttributeValue DataType="http://www.w3.org/2001/XMLSchema#string">
                        <xsl:value-of select="$user"/>
                    </AttributeValue>
                </xsl:for-each>
            </Apply>
        </Apply>
    </xsl:template>

    <xsl:template match="read" mode="role">
        <Apply FunctionId="urn:oasis:names:tc:xacml:1.0:function:string-at-least-one-member-of">
            <SubjectAttributeDesignator DataType="http://www.w3.org/2001/XMLSchema#string" MustBePresent="false" AttributeId="fedoraRole"/>
            <Apply FunctionId="urn:oasis:names:tc:xacml:1.0:function:string-bag">
                <xsl:variable name="all" as="xs:string*">
                    <xsl:for-each select="role">
                        <xsl:variable name="role" select="."/>
                        <xsl:sequence select="$role"/>
                        <xsl:if test="exists($roles)">
                            <xsl:sequence select="$roles//role[.=$role]/following-sibling::role"/>
                        </xsl:if>
                    </xsl:for-each>
                </xsl:variable>
                <xsl:for-each select="distinct-values($all)">
                    <xsl:variable name="role" select="."/>
                    <xsl:message>INF: read access for any [<xsl:value-of select="$role"/>]!</xsl:message>
                    <AttributeValue DataType="http://www.w3.org/2001/XMLSchema#string">
                        <xsl:value-of select="$role"/>
                    </AttributeValue>
                </xsl:for-each>
            </Apply>
        </Apply>
    </xsl:template>
    
    <xsl:template match="write" mode="role">
        <Apply FunctionId="urn:oasis:names:tc:xacml:1.0:function:string-at-least-one-member-of">
            <SubjectAttributeDesignator DataType="http://www.w3.org/2001/XMLSchema#string" MustBePresent="false" AttributeId="fedoraRole"/>
            <Apply FunctionId="urn:oasis:names:tc:xacml:1.0:function:string-bag">
                <xsl:for-each select="role">
                    <xsl:variable name="role" select="."/>
                    <xsl:message>INF: <xsl:value-of select="local-name(..)"/> access for any [<xsl:value-of select="$role"/>]!</xsl:message>
                    <AttributeValue DataType="http://www.w3.org/2001/XMLSchema#string">
                        <xsl:value-of select="$role"/>
                    </AttributeValue>
                </xsl:for-each>
            </Apply>
        </Apply>
    </xsl:template>
    
    <xsl:template name="xacml">
        <xsl:param name="dsid" select="'OBJ'"/>
        <xsl:param name="visible" select="true()"/>
        <Policy xmlns="urn:oasis:names:tc:xacml:1.0:policy" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" PolicyId="islandora-xacml-editor-v1" RuleCombiningAlgId="urn:oasis:names:tc:xacml:1.0:rule-combining-algorithm:first-applicable">
            <Target>
                <Subjects>
                    <AnySubject/>
                </Subjects>
                <Resources>
                    <AnyResource/>
                </Resources>
                <Actions>
                    <AnyAction/>
                </Actions>
            </Target>
            <xsl:apply-templates mode="xacml" select="."/>
            <!-- read -->
            <!-- visible means that some info is available but the OBJ might be not (that needs read access) -->
            <Rule RuleId="deny-dsid-mime" Effect="Deny">
                <Target>
                    <Subjects>
                        <AnySubject/>
                    </Subjects>
                    <Resources>
                        <Resource>
                            <ResourceMatch MatchId="urn:oasis:names:tc:xacml:1.0:function:string-equal">
                                <AttributeValue DataType="http://www.w3.org/2001/XMLSchema#string">
                                    <xsl:value-of select="$dsid"/>
                                </AttributeValue>
                                <ResourceAttributeDesignator DataType="http://www.w3.org/2001/XMLSchema#string" AttributeId="urn:fedora:names:fedora:2.1:resource:datastream:id"/>
                            </ResourceMatch>
                        </Resource>
                    </Resources>
                    <Actions>
                        <xsl:for-each select="$dsid-functions">
                            <Action>
                                <ActionMatch MatchId="urn:oasis:names:tc:xacml:1.0:function:string-equal">
                                    <AttributeValue DataType="http://www.w3.org/2001/XMLSchema#string">
                                        <xsl:text>urn:fedora:names:fedora:2.1:action:</xsl:text>
                                        <xsl:value-of select="."/>
                                    </AttributeValue>
                                    <ActionAttributeDesignator AttributeId="urn:fedora:names:fedora:2.1:action:id" DataType="http://www.w3.org/2001/XMLSchema#string"/>
                                </ActionMatch>
                            </Action>
                        </xsl:for-each>
                    </Actions>
                </Target>
                <Condition FunctionId="urn:oasis:names:tc:xacml:1.0:function:not">
                    <xsl:choose>
                        <xsl:when test="exists(read/user) and exists(read/role)">
                            <Apply FunctionId="urn:oasis:names:tc:xacml:1.0:function:or">
                                <xsl:apply-templates select="read" mode="user"/>
                                <xsl:apply-templates select="read" mode="role"/>
                            </Apply>
                        </xsl:when>
                        <xsl:when test="exists(read/user)">
                            <xsl:apply-templates select="read" mode="user"/>
                        </xsl:when>
                        <xsl:when test="exists(read/role)">
                            <xsl:apply-templates select="read" mode="role"/>
                        </xsl:when>
                    </xsl:choose>
                </Condition>
            </Rule>
            <xsl:if test="not($visible)">
                <!-- invisible means no access at all -->
                <Rule RuleId="deny-access-functions" Effect="Deny">
                    <Target>
                        <Subjects>
                            <AnySubject/>
                        </Subjects>
                        <Resources>
                            <AnyResource/>
                        </Resources>
                        <Actions>
                            <xsl:for-each select="$access-functions">
                                <Action>
                                    <ActionMatch MatchId="urn:oasis:names:tc:xacml:1.0:function:string-equal">
                                        <AttributeValue DataType="http://www.w3.org/2001/XMLSchema#string">
                                            <xsl:text>urn:fedora:names:fedora:2.1:action:</xsl:text>
                                            <xsl:value-of select="."/>
                                        </AttributeValue>
                                        <ActionAttributeDesignator AttributeId="urn:fedora:names:fedora:2.1:action:id" DataType="http://www.w3.org/2001/XMLSchema#string"/>
                                    </ActionMatch>
                                </Action>
                            </xsl:for-each>
                        </Actions>
                    </Target>
                    <Condition FunctionId="urn:oasis:names:tc:xacml:1.0:function:not">
                        <xsl:choose>
                            <xsl:when test="exists(read/user) and exists(read/role)">
                                <Apply FunctionId="urn:oasis:names:tc:xacml:1.0:function:or">
                                    <xsl:apply-templates select="read" mode="user"/>
                                    <xsl:apply-templates select="read" mode="role"/>
                                </Apply>
                            </xsl:when>
                            <xsl:when test="exists(read/user)">
                                <xsl:apply-templates select="read" mode="user"/>
                            </xsl:when>
                            <xsl:when test="exists(read/role)">
                                <xsl:apply-templates select="read" mode="role"/>
                            </xsl:when>
                        </xsl:choose>
                    </Condition>
                </Rule>
            </xsl:if>
            <!-- write -->
            <Rule RuleId="deny-management-functions" Effect="Deny">
                <Target>
                    <Subjects>
                        <AnySubject/>
                    </Subjects>
                    <Resources>
                        <AnyResource/>
                    </Resources>
                    <Actions>
                        <xsl:for-each select="$management-functions">
                            <Action>
                                <ActionMatch MatchId="urn:oasis:names:tc:xacml:1.0:function:string-equal">
                                    <AttributeValue DataType="http://www.w3.org/2001/XMLSchema#string">
                                        <xsl:text>urn:fedora:names:fedora:2.1:action:</xsl:text>
                                        <xsl:value-of select="."/>
                                    </AttributeValue>
                                    <ActionAttributeDesignator AttributeId="urn:fedora:names:fedora:2.1:action:id" DataType="http://www.w3.org/2001/XMLSchema#string"/>
                                </ActionMatch>
                            </Action>
                        </xsl:for-each>
                    </Actions>
                </Target>
                <Condition FunctionId="urn:oasis:names:tc:xacml:1.0:function:not">
                    <xsl:choose>
                        <xsl:when test="exists(write/user) and exists(write/role)">
                            <Apply FunctionId="urn:oasis:names:tc:xacml:1.0:function:or">
                                <xsl:apply-templates select="write" mode="user"/>
                                <xsl:apply-templates select="write" mode="role"/>
                            </Apply>
                        </xsl:when>
                        <xsl:when test="exists(write/user)">
                            <xsl:apply-templates select="write" mode="user"/>
                        </xsl:when>
                        <xsl:when test="exists(write/role)">
                            <xsl:apply-templates select="write" mode="role"/>
                        </xsl:when>
                    </xsl:choose>
                </Condition>
            </Rule>
            <Rule RuleId="allow-everything-else" Effect="Permit">
                <Target>
                    <Subjects>
                        <AnySubject/>
                    </Subjects>
                    <Resources>
                        <AnyResource/>
                    </Resources>
                    <Actions>
                        <AnyAction/>
                    </Actions>
                </Target>
            </Rule>
        </Policy>
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
        <xsl:message>DBG: SIP policy[<xsl:value-of select="$href-base"/>.xml]</xsl:message>
        <xsl:result-document href="{$href-base}.xml">
            <xsl:call-template name="xacml">
                <xsl:with-param name="dsid" select="'CMD'"/>
                <xsl:with-param name="visible" select="$visible"/>
            </xsl:call-template>
        </xsl:result-document>
        
        <xsl:message>DBG: SIP RELS-EXT[<xsl:value-of select="$href-base"/>.RELS-EXT.xml]</xsl:message>
        <xsl:result-document href="{$href-base}.RELS-EXT.xml">
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
        <xsl:result-document href="{$href-base}.xml">
            <xsl:call-template name="xacml">
                <xsl:with-param name="dsid" select="'OBJ'"/>
                <xsl:with-param name="visible" select="$visible"/>
            </xsl:call-template>
        </xsl:result-document>
        
        <xsl:message>DBG: resource RELS-EXT[<xsl:value-of select="$href-base"/>,RELS-EXT.xml]</xsl:message>
        <xsl:result-document href="{$href-base}.RELS-EXT.xml">
            <xsl:call-template name="rels-ext">
                <xsl:with-param name="visible" select="$visible"/>
            </xsl:call-template>
        </xsl:result-document>        
    </xsl:template>
    
</xsl:stylesheet>