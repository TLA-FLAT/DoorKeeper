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
    
    <xsl:template match="foxml:datastream/foxml:datastreamVersion[empty(following-sibling::foxml:datastreamVersion)]/foxml:xmlContent">
        <xsl:variable name="ds" select="replace(ancestor::foxml:datastream/@ID,'\.[0-9]+','')"/>
        <!-- either explicitly split off or explcitily skip from splitting of -->
        <xsl:variable name="do" select="$ds=$split or not($ds=$skip)"/>
        <xsl:if test="$do">
            <xsl:variable name="out" select="replace(base-uri(),'.xml$',concat('.',$ds,'.xml'))"/> 
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
            <xsl:variable name="out" select="replace(base-uri(),'.xml$',concat('.',$ds,'.file'))"/> 
            <xsl:message>split fox[<xsl:value-of select="base-uri()"/>][<xsl:value-of select="replace($out,'.*/','.../')"/>]</xsl:message>
            <xsl:result-document href="{$out}" method="text" encoding="UTF-8">
                <xsl:value-of select="@REF"/>
            </xsl:result-document>
        </xsl:if>
    </xsl:template>

</xsl:stylesheet>
