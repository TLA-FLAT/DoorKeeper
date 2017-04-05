<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:xs="http://www.w3.org/2001/XMLSchema"
    xmlns:cmd="http://www.clarin.eu/cmd/"
    xmlns:lat="http://lat.mpi.nl/"
    xmlns:sx="java:nl.mpi.tla.saxon"
    exclude-result-prefixes="xs"
    version="2.0">
    
    <xsl:param name="old-pid"/>
    <xsl:param name="new-pid"/>
    
    <xsl:param name="prefix"/>
    <xsl:param name="new-pid-eval" select="'true()'"/>
    
    <xsl:key name="rp" match="cmd:ResourceProxy" use="cmd:ResourceRef"/>
    <xsl:key name="rp-uri" match="cmd:ResourceProxy" use="cmd:ResourceRef/@lat:flatURI"/>
    
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

    <xsl:template match="cmd:ResourceRef[.=$old-pid]">
        <xsl:copy>
            <xsl:apply-templates select="@*"/>
            <xsl:value-of select="$new-pid"/>
        </xsl:copy>
    </xsl:template>
    
    <xsl:template match="/">
        <xsl:choose>
            <xsl:when test="empty(key('rp',$old-pid)) and empty(key('rp-uri',$old-pid))">
                <xsl:message>ERR: [<xsl:value-of select="$old-pid"/>] is not a member of this collection!</xsl:message>
                <null/>
            </xsl:when>
            <xsl:when test="exists(key('rp',$new-pid)) or exists(key('rp-uri',$new-pid))">
                <xsl:message>WRN: [<xsl:value-of select="$new-pid"/>] is already a member of this collection!</xsl:message>
                <null/>
            </xsl:when>
            <xsl:otherwise>
                <xsl:apply-templates/>
            </xsl:otherwise>
        </xsl:choose>
    </xsl:template>
    
</xsl:stylesheet>