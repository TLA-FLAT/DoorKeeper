<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:xs="http://www.w3.org/2001/XMLSchema"
    xmlns:cmd="http://www.clarin.eu/cmd/"
    xmlns:lat="http://lat.mpi.nl/"
    xmlns:sx="java:nl.mpi.tla.saxon"
    exclude-result-prefixes="xs"
    version="2.0">
    
    <xsl:param name="pid"/>
    <xsl:param name="fid"/>
    <xsl:param name="prefix"/>
    <xsl:param name="new-pid-eval" select="'true()'"/>
    
    <xsl:key name="rp" match="cmd:ResourceProxy" use="cmd:ResourceRef"/>
    <xsl:key name="rp-uri" match="cmd:ResourceProxy" use="cmd:ResourceRef/@lat:localURI"/>
    
    <xsl:variable name="namespaces">
        <ns/>
    </xsl:variable>
    <xsl:variable name="NS" select="$namespaces/descendant-or-self::ns"/>
    
    <xsl:template match="node() | @*">
        <xsl:copy>
            <xsl:apply-templates select="node() | @*"/>
        </xsl:copy>
    </xsl:template>
    
    <xsl:template match="cmd:MdSelfLink">
        <xsl:copy>
            <xsl:copy-of select="@*"/>
            <xsl:choose>
                <xsl:when test="sx:evaluate(/, $new-pid-eval, $NS)">
                    <xsl:text>http://hdl.handle.net/</xsl:text>
                    <xsl:value-of select="$prefix"/>
                    <xsl:text>/</xsl:text>
                    <xsl:value-of select="sx:uuid()"/>
                </xsl:when>
                <xsl:otherwise>
                    <xsl:value-of select="."/>
                </xsl:otherwise>
            </xsl:choose>
        </xsl:copy>
    </xsl:template>

    <xsl:template match="cmd:ResourceProxyList">
        <xsl:copy>
            <xsl:apply-templates select="node() | @*"/>
            <cmd:ResourceProxy id="rp-{sx:uuid()}">
                <cmd:ResourceType mimetype="application/x-cmdi+xml">Metadata</cmd:ResourceType>
                <cmd:ResourceRef lat:flatURI="{$fid}">
                    <xsl:value-of select="$pid"/>
                </cmd:ResourceRef>
            </cmd:ResourceProxy>
        </xsl:copy>
    </xsl:template>
    
    <xsl:template match="/">
        <xsl:choose>
            <xsl:when test="key('rp',$pid) or key('rp-uri',$pid)">
                <xsl:message>DBG: [<xsl:value-of select="$pid"/>] is already a member of this collection!</xsl:message>
                <null/>
            </xsl:when>
            <xsl:when test="key('rp',$fid) or key('rp-uri',$fid)">
                <xsl:message>DBG: [<xsl:value-of select="$fid"/>] is already a member of this collection!</xsl:message>
                <null/>
            </xsl:when>
            <xsl:otherwise>
                <xsl:apply-templates/>
            </xsl:otherwise>
        </xsl:choose>
    </xsl:template>
    
</xsl:stylesheet>