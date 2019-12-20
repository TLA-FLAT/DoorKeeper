<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet
    xmlns:xsl="http://www.w3.org/1999/XSL/Transform" version="3.0"
    xmlns:xs="http://www.w3.org/2001/XMLSchema"
    xmlns:foxml="info:fedora/fedora-system:def/foxml#"
    xmlns:cmd="http://www.clarin.eu/cmd/"
    xmlns:lat="http://lat.mpi.nl/"
    xmlns:sx="java:nl.mpi.tla.saxon"
    exclude-result-prefixes="xs sx lat">
    
    <xsl:template match="text()"/>
    
    <xsl:param name="split" select="()"/>
    <xsl:param name="skip" select="()"/>
    
    <xsl:param name="asof" select="
        round(
            ( current-dateTime() - xs:dateTime('1970-01-01T00:00:00') )
            div
            xs:dayTimeDuration('PT0.001S')
        )"/>
    
    <xsl:template match="foxml:objectProperties">
        <xsl:variable name="out" select="replace(base-uri(),'.xml$',concat('.',$asof,'.props'))"/>
        <xsl:message>split fox[<xsl:value-of select="base-uri()"/>][<xsl:value-of select="replace($out,'.*/','.../')"/>]</xsl:message>
        <xsl:result-document href="{$out}">
            <xsl:copy-of select="."/>
        </xsl:result-document>
    </xsl:template>
    
    <xsl:template match="foxml:datastream/foxml:datastreamVersion[empty(following-sibling::foxml:datastreamVersion)]/foxml:xmlContent">
        <xsl:variable name="ds" select="replace(ancestor::foxml:datastream/@ID,'\.[0-9]+','')"/>
        <!-- either explicitly split off or explcitily skip from splitting of -->
        <xsl:variable name="do" select="$ds=$split or not($ds=$skip)"/>
        <xsl:if test="$do">
            <xsl:variable name="out" select="replace(base-uri(),'.xml$',concat('.',$ds,'.',$asof,'.xml'))"/>
            <xsl:message>split fox[<xsl:value-of select="base-uri()"/>][<xsl:value-of select="replace($out,'.*/','.../')"/>]</xsl:message>
            <xsl:result-document href="{$out}">
                <xsl:copy-of select="*"/>
            </xsl:result-document>
        </xsl:if>
    </xsl:template>
    
    <xsl:template match="foxml:datastream/foxml:datastreamVersion[empty(following-sibling::foxml:datastreamVersion)]/foxml:contentLocation">
        <xsl:variable name="ds" select="replace(ancestor::foxml:datastream/@ID,'\.[0-9]+','')"/>
        <!-- either explicitly split off or explcitily skip from splitting of -->
        <xsl:variable name="do" select="$ds=$split or not($ds=$skip)"/>
        <xsl:if test="$do">
            <xsl:variable name="out" select="replace(base-uri(),'.xml$',concat('.',$ds,'.',$asof,'.file'))"/>
            <xsl:message>split fox[<xsl:value-of select="base-uri()"/>][<xsl:value-of select="replace($out,'.*/','.../')"/>]</xsl:message>
            <xsl:result-document href="{$out}">
                <xsl:copy-of select="parent::foxml:datastreamVersion"/>
            </xsl:result-document>
        </xsl:if>
    </xsl:template>

</xsl:stylesheet>
