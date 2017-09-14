<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:xs="http://www.w3.org/2001/XMLSchema"
    xmlns:cmd="http://www.clarin.eu/cmd/"
    xmlns:lat="http://lat.mpi.nl/"
    xmlns:sx="java:nl.mpi.tla.saxon"
    exclude-result-prefixes="xs"
    version="2.0">
    
    <xsl:param name="fid"/>

    <xsl:param name="old-pid"/>
    <xsl:param name="new-pid"/>
    
    <xsl:param name="prefix"/>
    <xsl:param name="new-pid-eval" select="'true()'"/>
    
    <xsl:key name="rp" match="cmd:ResourceProxy" use="replace(cmd:ResourceRef,'http(s)?://hdl.handle.net/','hdl:')"/>
    
    <xsl:variable name="namespaces">
        <ns/>
    </xsl:variable>
    <xsl:variable name="NS" select="$namespaces/descendant-or-self::ns"/>
    
    <xsl:template match="node() | @*" mode="#all">
        <xsl:copy>
            <xsl:apply-templates select="node() | @*" mode="#current"/>
        </xsl:copy>
    </xsl:template>
    
    <xsl:template match="cmd:MdSelfLink" mode="#all">
        <xsl:copy>
            <xsl:apply-templates select="@*" mode="#current"/>
            <xsl:choose>
                <xsl:when test="sx:evaluate(/, $new-pid-eval, $NS)">
                    <xsl:text>https://hdl.handle.net/</xsl:text>
                    <xsl:value-of select="$prefix"/>
                    <xsl:text>/</xsl:text>
                    <xsl:value-of select="sx:uuid()"/>
                </xsl:when>
                <xsl:otherwise>
                    <xsl:value-of select="replace(.,'hdl:','https://hdl.handle.net/')"/>
                </xsl:otherwise>
            </xsl:choose>
        </xsl:copy>
    </xsl:template>
    
    <xsl:template match="cmd:ResourceProxyList" mode="insert">
        <xsl:copy>
            <xsl:apply-templates select="node() | @*" mode="#current"/>
            <xsl:message>DBG: = insert ResourceProxy[<xsl:value-of select="$new-pid"/>]</xsl:message>
            <cmd:ResourceProxy id="rp-{sx:uuid()}">
                <cmd:ResourceType mimetype="application/x-cmdi+xml">Metadata</cmd:ResourceType>
                <cmd:ResourceRef>
                    <xsl:if test="normalize-space($fid)!=''">
                        <xsl:attribute name="lat:flatURI" select="$fid"/>
                    </xsl:if>
                    <xsl:value-of select="replace($new-pid,'hdl:','https://hdl.handle.net/')"/>
                </cmd:ResourceRef>
            </cmd:ResourceProxy>
        </xsl:copy>
    </xsl:template>
    
    <xsl:template match="cmd:ResourceRef[replace(.,'http(s)?://hdl.handle.net/','hdl:')=replace($old-pid,'http(s)?://hdl.handle.net/','hdl:')]" mode="update">
        <xsl:message>DBG: = update ResourceProxy[<xsl:value-of select="$old-pid"/>] -> [<xsl:value-of select="$new-pid"/>]</xsl:message>
        <xsl:if test="normalize-space($fid)!='' and normalize-space(@lat:flatURI)!='' and normalize-space(replace($fid,'#.*',''))!=normalize-space(replace(@lat:flatURI,'#.*',''))">
            <xsl:message terminate="yes">ERR: existing Resource[<xsl:value-of select="$old-pid"/>] has different FID[<xsl:value-of select="replace(@lat:flatURI,'#.*','')"/>] (expected FID[<xsl:value-of select="replace($fid,'#.*','')"/>])!</xsl:message>
        </xsl:if>
        <xsl:copy>
            <xsl:apply-templates select="@* except @lat:flatURI" mode="#current"/>
            <xsl:if test="normalize-space($fid)!=''">
                <xsl:attribute name="lat:flatURI" select="$fid"/>
            </xsl:if>
            <xsl:value-of select="replace($new-pid,'hdl:','https://hdl.handle.net/')"/>
        </xsl:copy>
    </xsl:template>
    
    <xsl:template match="/">
        <xsl:message>DBG: upsert-collections.xsl:</xsl:message>
        <xsl:message>DBG: - fid[<xsl:value-of select="$fid"/>]</xsl:message>
        <xsl:message>DBG: - old-pid[<xsl:value-of select="$old-pid"/>]</xsl:message>
        <xsl:message>DBG: - new-pid[<xsl:value-of select="$new-pid"/>]</xsl:message>
        <xsl:message>DBG: - prefix[<xsl:value-of select="$prefix"/>]</xsl:message>
        <xsl:message>DBG: - new-pid-eval[<xsl:value-of select="$new-pid-eval"/>]</xsl:message>
        <xsl:message>DBG: * MdSelfLink[<xsl:value-of select="//cmd:MdSelfLink/@lat:flatURI"/>][<xsl:value-of select="//cmd:MdSelfLink/@lat:localURI"/>][<xsl:value-of select="//cmd:MdSelfLink"/>]</xsl:message>
        <xsl:message>DBG: * ResourceProxies[<xsl:value-of select="string-join(//cmd:ResourceRef,', ')"/>]</xsl:message>
        <xsl:choose>
            <xsl:when test="normalize-space($new-pid)=''">
                <xsl:message terminate="yes">ERR: no (new) PID for the (new) resource!</xsl:message>
            </xsl:when>
            <xsl:when test="exists(key('rp',$new-pid))">
                <xsl:message>DBG: [<xsl:value-of select="$new-pid"/>] is already a member of this collection!</xsl:message>
            </xsl:when>
            <xsl:when test="normalize-space($old-pid)!='' and exists(key('rp',replace($old-pid,'http(s)?://hdl.handle.net/','hdl:')))">
                <xsl:message>DBG: > update [<xsl:value-of select="$old-pid"/>] -> [<xsl:value-of select="$new-pid"/>]</xsl:message>
                <xsl:apply-templates mode="update"/>
            </xsl:when>
            <xsl:when test="normalize-space($old-pid)!=''">
                <xsl:message terminate="yes">ERR: [<xsl:value-of select="$old-pid"/>] is not a member of this collection!</xsl:message>
            </xsl:when>
            <xsl:otherwise>
                <xsl:message>DBG: > insert [<xsl:value-of select="$new-pid"/>]</xsl:message>
                <xsl:apply-templates mode="insert"/>
            </xsl:otherwise>
        </xsl:choose>
    </xsl:template>
    
</xsl:stylesheet>