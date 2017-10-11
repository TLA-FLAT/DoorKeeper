<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:xs="http://www.w3.org/2001/XMLSchema"
    xmlns:sx="java:nl.mpi.tla.saxon"
    exclude-result-prefixes="xs sx"
    version="2.0">
    
    <xsl:param name="user"/>
    <xsl:param name="pass"/>
    
    <xsl:template match="node() | @*">
        <xsl:copy>
            <xsl:apply-templates select="node() | @*"/>
        </xsl:copy>
    </xsl:template>
    
    <xsl:template match="userName">
        <xsl:copy>
            <xsl:value-of select="$user/user/name"/>
        </xsl:copy>
    </xsl:template>
    
    <xsl:template match="userPass">
        <xsl:copy>
            <xsl:value-of select="$pass"/>
        </xsl:copy>
    </xsl:template>

</xsl:stylesheet>