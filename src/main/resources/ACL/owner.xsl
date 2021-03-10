<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform" xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:cmd="http://www.clarin.eu/cmd/" xmlns:lat="http://lat.mpi.nl/" xmlns:sem="http://marklogic.com/semantics" xmlns:functx="http://www.functx.com" exclude-result-prefixes="xs cmd lat sem functx" version="3.0">

    <xsl:param name="acl-base" select="'.'"/>
    <xsl:param name="overwrite-user-name" select="()"/>

    <xsl:variable name="debug" select="false()" static="yes"/>

    <xsl:variable name="t" select="/"/>
    <xsl:key name="t-subject" match="sem:triple" use="sem:subject"/>
    <xsl:key name="t-predicate" match="sem:triple" use="sem:predicate"/>
    <xsl:key name="t-object" match="sem:triple" use="sem:object"/>

    <xsl:function name="functx:min-non-empty-string" as="xs:string?">
        <xsl:param name="strings" as="xs:string*"/>
        <xsl:sequence select="min($strings[. != ''])"/>
    </xsl:function>

    <xsl:variable name="acl-accessTo" select="'http://www.w3.org/ns/auth/acl#accessTo'"/>
    <xsl:variable name="acl-agent" select="'http://www.w3.org/ns/auth/acl#agent'"/>
    <xsl:variable name="acl-agentClass" select="'http://www.w3.org/ns/auth/acl#agentClass'"/>
    <xsl:variable name="acl-mode" select="'http://www.w3.org/ns/auth/acl#mode'"/>
    <xsl:variable name="acl-read" select="'http://www.w3.org/ns/auth/acl#Read'"/>
    <xsl:variable name="acl-write" select="'http://www.w3.org/ns/auth/acl#Write'"/>
    <xsl:variable name="acl-hide" select="'http://lat.mpi.nl/ns/auth/acl#Hide'"/>
    <xsl:variable name="foaf-agent" select="'http://xmlns.com/foaf/0.1/Agent'"/>
    <xsl:variable name="foaf-service" select="'http://xmlns.com/foaf/0.1/accountServiceHomepage'"/>
    <xsl:variable name="foaf-account" select="'http://xmlns.com/foaf/0.1/account'"/>
    <xsl:variable name="foaf-accountName" select="'http://xmlns.com/foaf/0.1/accountName'"/>

    <xsl:variable name="sip" select="functx:min-non-empty-string(key('t-predicate', $acl-accessTo, $t)/sem:object)"/>
    <xsl:variable name="flat" select="distinct-values(key('t-predicate', $foaf-service, $t)/sem:object)[ends-with(., '#flat')]"/>
    <xsl:variable name="owner" select="distinct-values(key('t-predicate', $acl-agent, $t)/sem:object)[ends-with(., '#owner')]"/>

    <xsl:template match="/">
        <xsl:message use-when="$debug">DBG: sip[<xsl:value-of select="$sip"/>]</xsl:message>
        <xsl:choose>
            <xsl:when test="normalize-space($overwrite-user-name) != ''">
                <xsl:call-template name="overwrite"/>
            </xsl:when>
            <xsl:otherwise>
                <xsl:message use-when="$debug">DBG: flat[<xsl:value-of select="$flat"/>]</xsl:message>
                <xsl:message use-when="$debug">DBG: owner[<xsl:value-of select="$owner"/>]</xsl:message>
                <xsl:result-document href="{$acl-base}/owner.xml">
                    <user>
                        <xsl:for-each select="key('t-subject', $owner, $t)[sem:predicate = $foaf-account]/sem:object">
                            <xsl:variable name="account" select="."/>
                            <xsl:message use-when="$debug">DBG: owner account[<xsl:value-of select="$account"/>]</xsl:message>
                            <!-- does the agent have a FLAT account? -->
                            <xsl:if test="key('t-subject', $account, $t)[sem:predicate = $foaf-service]/sem:object = $flat">
                                <xsl:for-each select="key('t-subject', $account, $t)[sem:predicate = $foaf-accountName]/sem:object">
                                    <xsl:variable name="eppn" select="."/>
                                    <xsl:message use-when="$debug">DBG: owner eppn[<xsl:value-of select="$eppn"/>]</xsl:message>
                                    <name>
                                        <xsl:value-of select="$eppn"/>
                                    </name>
                                </xsl:for-each>
                            </xsl:if>
                        </xsl:for-each>
                    </user>
                </xsl:result-document>
            </xsl:otherwise>
        </xsl:choose>
    </xsl:template>

    <xsl:template name="overwrite">
        <xsl:message>WRN: owner overwrite[<xsl:value-of select="$overwrite-user-name"/>]</xsl:message>
        <xsl:result-document href="{$acl-base}/owner.xml">
            <user>
                <name>
                    <xsl:value-of select="$overwrite-user-name"/>
                </name>
            </user>
        </xsl:result-document>
    </xsl:template>

</xsl:stylesheet>
