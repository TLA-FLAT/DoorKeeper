<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:xs="http://www.w3.org/2001/XMLSchema"
    xmlns:math="http://www.w3.org/2005/xpath-functions/math"
    xmlns:foxml="info:fedora/fedora-system:def/foxml#"
    exclude-result-prefixes="xs math"
    version="3.0">

    <xsl:output method="text" encoding="UTF-8"/>
    
    <xsl:variable name="NL" select="system-property('line.separator')"/>
    
    <xsl:template match="text()" mode="#all"/>
    
<!--
DELETE {
    <> <info:fedora/fedora-system:def/model#state> ?state .
    <> <info:fedora/fedora-system:def/model#ownerId> ?ownerId .
    <> <info:fedora/fedora-system:def/model#label> ?label .
}
INSERT {
        
    
    <> <info:fedora/fedora-system:def/model#state> "Active".
        
    
    <> <info:fedora/fedora-system:def/model#ownerId> "bob@meertens.knaw.nl" .
        
    
    <> <info:fedora/fedora-system:def/model#label> "The Green Hornet" .
        

} WHERE {
    <> <info:fedora/fedora-system:def/model#state> ?state .
    <> <info:fedora/fedora-system:def/model#ownerId> ?ownerId .
    <> <info:fedora/fedora-system:def/model#label> ?label .
    FILTER (!(langMatches(lang(?label),"*")))
}
  -->
    
    <xsl:template match="foxml:objectProperties">
        <xsl:text expand-text="yes">
DELETE {{
        </xsl:text>
        <xsl:apply-templates mode="delete"/>
        <xsl:text expand-text="yes">
}} INSERT {{
        </xsl:text>
        <xsl:apply-templates mode="insert"/>
        <xsl:text expand-text="yes">
}} WHERE {{
        </xsl:text>
        <xsl:apply-templates mode="where"/>
        <xsl:text expand-text="yes">
}}
        </xsl:text>
    </xsl:template>
    
    <xsl:template match="foxml:property" mode="delete">
        <xsl:text expand-text="yes">
    &lt;> &lt;{@NAME}> ?{substring-after(@NAME,'#')} .
        </xsl:text>
    </xsl:template>

    <xsl:template match="foxml:property" mode="insert">
        <xsl:text expand-text="yes">
    &lt;> &lt;{@NAME}> "{@VALUE}" .
        </xsl:text>
    </xsl:template>
    
    <xsl:template match="foxml:property[@NAME='info:fedora/fedora-system:def/model#state']" mode="insert">
        <xsl:variable name="val">
            <xsl:choose>
                <xsl:when test="@VALUE='A'">
                    <xsl:value-of select="'Active'"/>
                </xsl:when>
                <xsl:when test="@VALUE='I'">
                    <xsl:value-of select="'Inactive'"/>
                </xsl:when>
                <xsl:when test="@VALUE='D'">
                    <xsl:value-of select="'Deleted'"/>
                </xsl:when>
            </xsl:choose>
        </xsl:variable>
        <xsl:choose>
            <xsl:when test="normalize-space($val)!=''">
                <xsl:text expand-text="yes">
    &lt;> &lt;{@NAME}> "{$val}".
        </xsl:text>
            </xsl:when>
            <xsl:otherwise><!--ERROR--></xsl:otherwise>
        </xsl:choose>  
    </xsl:template>
    
    
    <xsl:template match="foxml:property" mode="where">
        <xsl:text expand-text="yes">
    &lt;> &lt;{@NAME}> ?{substring-after(@NAME,'#')} .
        </xsl:text>
        <xsl:choose>
            <xsl:when test="normalize-space(@xml:lang)!=''">
                <xsl:text expand-text="yes">
                    FILTER (!(langMatches(lang(?{substring-after(@NAME,'#')}),"{@xml:lang}")))
                </xsl:text>
            </xsl:when>
            <xsl:otherwise>
                <xsl:text expand-text="yes">
                    FILTER (!(langMatches(lang(?{substring-after(@NAME,'#')}),"*")))
                </xsl:text>
            </xsl:otherwise>
        </xsl:choose>
    </xsl:template>

</xsl:stylesheet>