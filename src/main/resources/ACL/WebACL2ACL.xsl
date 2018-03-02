<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform" xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:cmd="http://www.clarin.eu/cmd/" xmlns:lat="http://lat.mpi.nl/" xmlns:sem="http://marklogic.com/semantics" xmlns:functx="http://www.functx.com" exclude-result-prefixes="xs cmd lat sem functx" version="3.0">

    <xsl:param name="record" select="doc('./record.cmdi')"/>
    <xsl:param name="acl-base" select="'.'"/>

    <xsl:param name="default-accounts" select="()"/>
    <xsl:param name="default-roles" select="('administrator')"/>

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

    <xsl:function name="cmd:hdl">
        <xsl:param name="pid"/>
        <xsl:sequence select="replace(replace($pid, '^http(s?)://hdl.handle.net/', 'hdl:'), '@format=[a-z]+', '')"/>
    </xsl:function>

    <xsl:function name="cmd:lat">
        <xsl:param name="prefix"/>
        <xsl:param name="pid"/>
        <xsl:variable name="suffix" select="replace(replace(cmd:hdl($pid), '[^a-zA-Z0-9]', '_'), '^hdl_', '')"/>
        <xsl:variable name="length" select="
                min((string-length($suffix),
                (64 - string-length($prefix))))"/>
        <xsl:sequence select="concat($prefix, substring($suffix, string-length($suffix) - $length + 1))"/>
    </xsl:function>

    <xsl:function name="cmd:accountsWithAccess" as="xs:string*">
        <xsl:param name="objects"/>
        <xsl:param name="mode"/>
        <xsl:for-each select="($objects)[sem:predicate = $acl-accessTo]/sem:subject">
            <xsl:variable name="rule" select="."/>
            <xsl:message use-when="$debug">DBG: rule[<xsl:value-of select="$rule"/>]</xsl:message>
            <!-- does the rule give $mode access? -->
            <xsl:if test="exists(key('t-subject', $rule, $t)[sem:predicate = $acl-mode][sem:object = $mode])">
                <!-- go to the agents -->
                <xsl:for-each select="key('t-subject', $rule, $t)[sem:predicate = $acl-agent]/sem:object">
                    <xsl:variable name="agent" select="."/>
                    <xsl:message use-when="$debug">DBG: agent[<xsl:value-of select="$agent"/>]</xsl:message>
                    <!-- go to their accounts -->
                    <xsl:for-each select="key('t-subject', $agent, $t)[sem:predicate = $foaf-account]/sem:object">
                        <xsl:variable name="account" select="."/>
                        <xsl:message use-when="$debug">DBG: account[<xsl:value-of select="$account"/>]</xsl:message>
                        <!-- does the agent have a FLAT account? -->
                        <xsl:if test="key('t-subject', $account, $t)[sem:predicate = $foaf-service]/sem:object = $flat">
                            <xsl:for-each select="key('t-subject', $account, $t)[sem:predicate = $foaf-accountName]/sem:object">
                                <xsl:variable name="eppn" select="."/>
                                <xsl:message use-when="$debug">DBG: access[<xsl:value-of select="$mode"/>] for account[<xsl:value-of select="$eppn"/>]!</xsl:message>
                                <xsl:sequence select="$eppn"/>
                            </xsl:for-each>
                        </xsl:if>
                    </xsl:for-each>
                </xsl:for-each>
            </xsl:if>
        </xsl:for-each>
    </xsl:function>

    <xsl:function name="cmd:rolesWithAccess" as="xs:string*">
        <xsl:param name="objects"/>
        <xsl:param name="mode"/>
        <xsl:for-each select="($objects)[sem:predicate = $acl-accessTo]/sem:subject">
            <xsl:variable name="rule" select="."/>
            <xsl:message use-when="$debug">DBG: rule[<xsl:value-of select="$rule"/>]</xsl:message>
            <!-- does the rule give $mode access? -->
            <xsl:if test="exists(key('t-subject', $rule, $t)[sem:predicate = $acl-mode][sem:object = $mode])">
                <xsl:for-each select="key('t-subject', $rule, $t)[sem:predicate = $acl-agentClass]/sem:object">
                    <xsl:variable name="agent" select="."/>
                    <xsl:message use-when="$debug">DBG: agent class[<xsl:value-of select="$agent"/>]</xsl:message>
                    <xsl:choose>
                        <!-- if the AgentClass is foaf:Agent the resource should be public, as foaf:Agent represents everyone -->
                        <xsl:when test="$agent = $foaf-agent">
                            <!-- should have been handled above, if we use the 'anonymous user' Drupal role the FC API-A will still require a login -->
                            <xsl:message terminate="yes">ERR: read access for everyone, but processing is now handling specific user(s| groups)!</xsl:message>
                        </xsl:when>
                        <xsl:otherwise>
                            <!-- go to their accounts -->
                            <xsl:for-each select="key('t-subject', $agent, $t)[sem:predicate = $foaf-account]/sem:object">
                                <xsl:variable name="account" select="."/>
                                <xsl:message use-when="$debug">DBG: account[<xsl:value-of select="$account"/>]</xsl:message>
                                <!-- does the agent have a FLAT account? -->
                                <xsl:if test="key('t-subject', $account, $t)[sem:predicate = $foaf-service]/sem:object = $flat">
                                    <xsl:for-each select="key('t-subject', $account, $t)[sem:predicate = $foaf-accountName]/sem:object">
                                        <xsl:variable name="role" select="."/>
                                        <xsl:message use-when="$debug">DBG: access[<xsl:value-of select="$mode"/>] for role[<xsl:value-of select="$role"/>]!</xsl:message>
                                        <xsl:sequence select="$role"/>
                                    </xsl:for-each>
                                </xsl:if>
                            </xsl:for-each>
                        </xsl:otherwise>
                    </xsl:choose>
                </xsl:for-each>
            </xsl:if>
        </xsl:for-each>
    </xsl:function>

    <xsl:template match="/">
        <xsl:message use-when="$debug">DBG: sip[<xsl:value-of select="$sip"/>]</xsl:message>
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
        <acl>
            <!-- SIP, aka metadata, CMD record or compound -->
            <sip>
                <xsl:variable name="sipPID">
                    <xsl:choose>
                        <xsl:when test="starts-with(cmd:hdl($record/cmd:CMD/cmd:Header/cmd:MdSelfLink), 'hdl:')">
                            <xsl:sequence select="cmd:hdl($record/cmd:CMD/cmd:Header/cmd:MdSelfLink)"/>
                        </xsl:when>
                        <xsl:otherwise>
                            <xsl:sequence select="resolve-uri($record/cmd:CMD/cmd:Header/cmd:MdSelfLink, base-uri($record))"/>
                        </xsl:otherwise>
                    </xsl:choose>
                </xsl:variable>
                <xsl:attribute name="pid" select="$sipPID"/>
                <xsl:variable name="sipID">
                    <xsl:choose>
                        <xsl:when test="normalize-space($record/cmd:CMD/cmd:Header/cmd:MdSelfLink/@lat:flatURI) != ''">
                            <xsl:sequence select="normalize-space($record/cmd:CMD/cmd:Header/cmd:MdSelfLink/@lat:flatURI)"/>
                        </xsl:when>
                        <xsl:otherwise>
                            <xsl:sequence select="cmd:lat('lat:', $sipPID)"/>
                        </xsl:otherwise>
                    </xsl:choose>
                </xsl:variable>
                <xsl:attribute name="id" select="$sipID"/>
                <read>
                    <!-- if hide set read rights else public -->
                    <xsl:variable name="hide" as="xs:boolean*">
                        <xsl:for-each select="(key('t-object', $sip, $t))[sem:predicate = $acl-accessTo]/sem:subject">
                            <xsl:variable name="rule" select="."/>
                            <xsl:message use-when="$debug">DBG: SIP rule[<xsl:value-of select="$rule"/>]</xsl:message>
                            <xsl:for-each use-when="$debug" select="key('t-subject', $rule, $t)">
                                <xsl:message use-when="$debug">DBG: s[<xsl:value-of select="sem:subject"/>]p[<xsl:value-of select="sem:predicate"/>]o[<xsl:value-of select="sem:object"/>]</xsl:message>
                            </xsl:for-each>
                            <!-- does the rule hide access? -->
                            <xsl:if test="exists(key('t-subject', $rule, $t)[sem:predicate = $acl-mode][sem:object = $acl-hide])">
                                <xsl:message use-when="$debug">DBG: hidden SIP[<xsl:value-of select="$record/cmd:CMD/cmd:Header/cmd:MdSelfLink/@lat:localURI"/>][<xsl:value-of select="$record/cmd:CMD/cmd:Header/cmd:MdSelfLink/@lat:flatURI"/>][<xsl:value-of select="$record/cmd:CMD/cmd:Header/cmd:MdSelfLink"/>]</xsl:message>
                                <xsl:sequence select="true()"/>
                            </xsl:if>
                        </xsl:for-each>
                    </xsl:variable>
                    <xsl:choose>
                        <xsl:when test="$hide">
                            <!-- list users/roles with read access -->
                            <xsl:for-each select="$default-accounts">
                                <xsl:variable name="account" select="."/>
                                <xsl:message use-when="$debug">DBG: SIP read access for account[<xsl:value-of select="$account"/>]!</xsl:message>
                                <user>
                                    <xsl:value-of select="$account"/>
                                </user>
                            </xsl:for-each>
                            <xsl:for-each select="cmd:accountsWithAccess(key('t-object', $sip, $t), $acl-read)">
                                <xsl:variable name="account" select="."/>
                                <xsl:message use-when="$debug">DBG: SIP read access for account[<xsl:value-of select="$account"/>]!</xsl:message>
                                <user>
                                    <xsl:value-of select="$account"/>
                                </user>
                            </xsl:for-each>
                            <xsl:for-each select="$default-roles">
                                <xsl:variable name="role" select="."/>
                                <xsl:message use-when="$debug">DBG: SIP read access for any [<xsl:value-of select="$role"/>]!</xsl:message>
                                <role>
                                    <xsl:value-of select="$role"/>
                                </role>
                            </xsl:for-each>
                            <xsl:for-each select="cmd:rolesWithAccess(key('t-object', $sip, $t), $acl-read)">
                                <xsl:variable name="role" select="."/>
                                <xsl:message use-when="$debug">DBG: SIP read access for any [<xsl:value-of select="$role"/>]!</xsl:message>
                                <role>
                                    <xsl:value-of select="$role"/>
                                </role>
                            </xsl:for-each>
                        </xsl:when>
                        <xsl:otherwise>
                            <xsl:message use-when="$debug">DBG: public SIP[<xsl:value-of select="$record/cmd:CMD/cmd:Header/cmd:MdSelfLink/@lat:localURI"/>][<xsl:value-of select="$record/cmd:CMD/cmd:Header/cmd:MdSelfLink/@lat:flatURI"/>][<xsl:value-of select="$record/cmd:CMD/cmd:Header/cmd:MdSelfLink"/>]</xsl:message>
                            <role>anonymous user</role>
                        </xsl:otherwise>
                    </xsl:choose>
                </read>
                <write>
                    <!-- list users/roles with write access -->
                    <xsl:for-each select="$default-accounts">
                        <xsl:variable name="account" select="."/>
                        <xsl:message use-when="$debug">DBG: SIP write access for account[<xsl:value-of select="$account"/>]!</xsl:message>
                        <user>
                            <xsl:value-of select="$account"/>
                        </user>
                    </xsl:for-each>
                    <xsl:for-each select="cmd:accountsWithAccess(key('t-object', $sip, $t), $acl-write)">
                        <xsl:variable name="account" select="."/>
                        <xsl:message use-when="$debug">DBG: SIP write access for account[<xsl:value-of select="$account"/>]!</xsl:message>
                        <user>
                            <xsl:value-of select="$account"/>
                        </user>
                    </xsl:for-each>
                    <xsl:for-each select="$default-roles">
                        <xsl:variable name="role" select="."/>
                        <xsl:message use-when="$debug">DBG: SIP write access for any [<xsl:value-of select="$role"/>]!</xsl:message>
                        <role>
                            <xsl:value-of select="$role"/>
                        </role>
                    </xsl:for-each>
                    <xsl:for-each select="cmd:rolesWithAccess(key('t-object', $sip, $t), $acl-write)">
                        <xsl:variable name="role" select="."/>
                        <xsl:message use-when="$debug">DBG: SIP write access for any [<xsl:value-of select="$role"/>]!</xsl:message>
                        <role>
                            <xsl:value-of select="$role"/>
                        </role>
                    </xsl:for-each>
                </write>
                <xsl:for-each select="$record/cmd:CMD/cmd:Resources/cmd:ResourceProxyList/cmd:ResourceProxy[cmd:ResourceType = 'Resource']">
                    <xsl:variable name="resource" select="."/>
                    <resource>
                        <xsl:variable name="rid" select="concat($sip, '#', $resource/@id)"/>
                        <xsl:message use-when="$debug">DBG: rid[<xsl:value-of select="$rid"/>]</xsl:message>
                        <xsl:variable name="resPID">
                            <xsl:choose>
                                <xsl:when test="starts-with(cmd:hdl($resource/cmd:ResourceRef), 'hdl:')">
                                    <xsl:sequence select="cmd:hdl($resource/cmd:ResourceRef)"/>
                                </xsl:when>
                                <xsl:otherwise>
                                    <xsl:sequence select="resolve-uri($resource/cmd:ResourceRef, base-uri($resource))"/>
                                </xsl:otherwise>
                            </xsl:choose>
                        </xsl:variable>
                        <xsl:attribute name="pid" select="$resPID"/>
                        <xsl:variable name="resID">
                            <xsl:choose>
                                <xsl:when test="normalize-space($resource/cmd:ResourceRef/@lat:flatURI) != ''">
                                    <xsl:sequence select="normalize-space($resource/cmd:ResourceRef/@lat:flatURI)"/>
                                </xsl:when>
                                <xsl:otherwise>
                                    <xsl:sequence select="cmd:lat('lat:', $resPID)"/>
                                </xsl:otherwise>
                            </xsl:choose>
                        </xsl:variable>
                        <xsl:attribute name="id" select="$resID"/>
                        <read>
                            <!-- determine if the SIP or resource is public -->
                            <xsl:variable name="public" as="xs:boolean*">
                                <xsl:for-each select="(key('t-object', $rid, $t), key('t-object', $sip, $t))[sem:predicate = $acl-accessTo]/sem:subject">
                                    <xsl:variable name="rule" select="."/>
                                    <xsl:message use-when="$debug">DBG: SIP or resource rule[<xsl:value-of select="$rule"/>]</xsl:message>
                                    <!-- does the rule give read access? -->
                                    <xsl:if test="exists(key('t-subject', $rule, $t)[sem:predicate = $acl-mode][sem:object = $acl-read])">
                                        <xsl:for-each select="key('t-subject', $rule, $t)[sem:predicate = $acl-agentClass]/sem:object">
                                            <xsl:variable name="agent" select="."/>
                                            <xsl:message use-when="$debug">DBG: agent class[<xsl:value-of select="$agent"/>]</xsl:message>
                                            <!-- if the AgentClass is foaf:Agent the resource should be public, as foaf:Agent represents everyone -->
                                            <xsl:if test="$agent = $foaf-agent">
                                                <xsl:message use-when="$debug">DBG: read access for everyone!</xsl:message>
                                                <xsl:sequence select="true()"/>
                                            </xsl:if>
                                        </xsl:for-each>
                                    </xsl:if>
                                </xsl:for-each>
                            </xsl:variable>
                            <xsl:message use-when="$debug">DBG: <xsl:value-of select="
                                        if ($public) then
                                            ('public')
                                        else
                                            ('private')"/> resource[<xsl:value-of select="$resource/cmd:ResourceRef/@lat:localURI"/>][<xsl:value-of select="$resource/cmd:ResourceRef/@lat:flatURI"/>][<xsl:value-of select="$resource/cmd:ResourceRef"/>]</xsl:message>
                            <xsl:choose>
                                <xsl:when test="$public">
                                    <role>anonymous user</role>
                                </xsl:when>
                                <xsl:otherwise>
                                    <xsl:for-each select="$default-accounts">
                                        <xsl:variable name="account" select="."/>
                                        <xsl:message use-when="$debug">DBG: read access for account[<xsl:value-of select="$account"/>]!</xsl:message>
                                        <user>
                                            <xsl:value-of select="$account"/>
                                        </user>
                                    </xsl:for-each>
                                    <xsl:for-each select="cmd:accountsWithAccess((key('t-object', $rid, $t), key('t-object', $sip, $t)), $acl-read)">
                                        <xsl:variable name="account" select="."/>
                                        <xsl:message use-when="$debug">DBG: read access for account[<xsl:value-of select="$account"/>]!</xsl:message>
                                        <user>
                                            <xsl:value-of select="$account"/>
                                        </user>
                                    </xsl:for-each>
                                    <xsl:for-each select="$default-roles">
                                        <xsl:variable name="role" select="."/>
                                        <xsl:message use-when="$debug">DBG: read access for any [<xsl:value-of select="$role"/>]!</xsl:message>
                                        <role>
                                            <xsl:value-of select="$role"/>
                                        </role>
                                    </xsl:for-each>
                                    <xsl:for-each select="cmd:rolesWithAccess((key('t-object', $rid, $t), key('t-object', $sip, $t)), $acl-read)">
                                        <xsl:variable name="role" select="."/>
                                        <xsl:message use-when="$debug">DBG: read access for any [<xsl:value-of select="$role"/>]!</xsl:message>
                                        <role>
                                            <xsl:value-of select="$role"/>
                                        </role>
                                    </xsl:for-each>
                                </xsl:otherwise>
                            </xsl:choose>
                        </read>
                        <write>
                            <xsl:for-each select="$default-accounts">
                                <xsl:variable name="account" select="."/>
                                <xsl:message use-when="$debug">DBG: read access for account[<xsl:value-of select="$account"/>]!</xsl:message>
                                <user>
                                    <xsl:value-of select="$account"/>
                                </user>
                            </xsl:for-each>
                            <xsl:for-each select="cmd:accountsWithAccess((key('t-object', $rid, $t), key('t-object', $sip, $t)), $acl-write)">
                                <xsl:variable name="account" select="."/>
                                <xsl:message use-when="$debug">DBG: read access for account[<xsl:value-of select="$account"/>]!</xsl:message>
                                <user>
                                    <xsl:value-of select="$account"/>
                                </user>
                            </xsl:for-each>
                            <xsl:for-each select="$default-roles">
                                <xsl:variable name="role" select="."/>
                                <xsl:message use-when="$debug">DBG: read access for any [<xsl:value-of select="$role"/>]!</xsl:message>
                                <role>
                                    <xsl:value-of select="$role"/>
                                </role>
                            </xsl:for-each>
                            <xsl:for-each select="cmd:rolesWithAccess((key('t-object', $rid, $t), key('t-object', $sip, $t)), $acl-write)">
                                <xsl:variable name="role" select="."/>
                                <xsl:message use-when="$debug">DBG: read access for any [<xsl:value-of select="$role"/>]!</xsl:message>
                                <role>
                                    <xsl:value-of select="$role"/>
                                </role>
                            </xsl:for-each>
                        </write>
                    </resource>
                </xsl:for-each>
            </sip>
        </acl>
    </xsl:template>

</xsl:stylesheet>
