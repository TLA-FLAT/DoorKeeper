<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet version="3.0"
    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:islandora="http://islandora.ca/ontology/relsext#"
    xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
>

    <!-- identity copy -->
    <xsl:template match="node() | @*">
        <xsl:copy>
            <xsl:apply-templates select="node() | @*"/>
        </xsl:copy>
    </xsl:template>
    
    <!-- delete props -->
    <xsl:template match="rdf:Description/*" priority="0"/>
    
    <!-- only keep isViewableBy and isManageableBy -->
    <xsl:template match="rdf:Description/islandora:isViewableByUser|rdf:Description/islandora:isViewableByRole|rdf:Description/islandora:isManageableByUser|rdf:Description/islandora:isManageableByRole" priority="1">
        <xsl:copy>
            <xsl:apply-templates select="node() | @*"/>
        </xsl:copy>
    </xsl:template>
 
</xsl:stylesheet>