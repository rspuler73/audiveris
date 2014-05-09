//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                   E n d i n g s B u i l d e r                                  //
//                                                                                                //
//------------------------------------------------------------------------------------------------//
// <editor-fold defaultstate="collapsed" desc="hdr">
//  Copyright © Herve Bitteur and others 2000-2014. All rights reserved.
//  This software is released under the GNU General Public License.
//  Goto http://kenai.com/projects/audiveris to report bugs or suggestions.
//------------------------------------------------------------------------------------------------//
// </editor-fold>
package omr.sheet.curve;

import omr.Main;

import omr.constant.Constant;
import omr.constant.ConstantSet;

import omr.glyph.GlyphLayer;
import omr.glyph.facets.BasicGlyph;
import omr.glyph.facets.Glyph;

import omr.grid.FilamentsFactory;
import omr.grid.StaffInfo;

import omr.lag.Section;
import omr.lag.Sections;

import omr.math.GeoOrder;
import omr.math.LineUtil;

import omr.run.Orientation;
import static omr.run.Orientation.VERTICAL;

import omr.sheet.Scale;
import omr.sheet.Sheet;
import omr.sheet.SystemInfo;

import omr.sig.BarlineInter;
import omr.sig.EndingBarRelation;
import omr.sig.EndingInter;
import omr.sig.GradeImpacts;
import omr.sig.Inter;
import omr.sig.SIGraph;
import omr.sig.SegmentInter;
import static omr.util.HorizontalSide.*;
import omr.util.Navigable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.geom.Line2D;
import java.awt.geom.Point2D;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Class {@code EndingsBuilder} retrieves the endings out of segments found in sheet
 * skeleton.
 *
 * @author Hervé Bitteur
 */
public class EndingsBuilder
{
    //~ Static fields/initializers -----------------------------------------------------------------

    private static final Constants constants = new Constants();

    private static final Logger logger = LoggerFactory.getLogger(EndingsBuilder.class);

    //~ Instance fields ----------------------------------------------------------------------------
    /** The related sheet. */
    @Navigable(false)
    protected final Sheet sheet;

    /** Curves environment. */
    protected final Curves curves;

    /** Scale-dependent parameters. */
    private final Parameters params;

    //~ Constructors -------------------------------------------------------------------------------
    /**
     * Creates a new WedgesBuilder object.
     *
     * @param curves curves environment
     */
    public EndingsBuilder (Curves curves)
    {
        this.curves = curves;
        sheet = curves.getSheet();
        params = new Parameters(sheet.getScale());
    }

    //~ Methods ------------------------------------------------------------------------------------
    //--------------//
    // buildEndings //
    //--------------//
    /**
     * Retrieve the endings among the sheet segment curves.
     * Endings are long horizontal segments, with a downward leg on the left side and optionally
     * another leg on the right side.
     * Each side is related to a distinct bar line.
     */
    public void buildEndings ()
    {
        List<SegmentInter> segments = curves.getSegments();

        for (SegmentInter segment : segments) {
            processSegment(segment);
        }
    }

    //-----------//
    // lookupBar //
    //-----------//
    /**
     * Look for a bar line vertically aligned with the ending side.
     * <p>
     * It is not very important to select a precise bar line within a group, since for left end we
     * choose the right-most bar and the opposite for right end.
     * We simply have to make sure that the lookup area is wide enough.
     * <p>
     * An ending which starts a staff may have its left side after the clef and key signature, which
     * means far after the starting bar line. Perhaps we should consider the staff DMZ in such case.
     *
     * @param seg        the horizontal segment
     * @param reverse    which side is at stake
     * @param staff      related staff
     * @param systemBars the collection of bar lines in the containing system
     * @return the selected bar line, or null if none
     */
    private BarlineInter lookupBar (SegmentInfo seg,
                                    boolean reverse,
                                    StaffInfo staff,
                                    List<Inter> systemBars)
    {
        Point end = seg.getEnd(reverse);
        Rectangle box = new Rectangle(end);
        box.grow(params.maxBarShift, 0);
        box.height = staff.getLastLine().yAt(end.x) - end.y;

        SystemInfo system = staff.getSystem();
        SIGraph sig = system.getSig();
        List<Inter> bars = sig.intersectedInters(systemBars, GeoOrder.NONE, box);
        Collections.sort(bars, Inter.byAbscissa);

        if (bars.isEmpty()) {
            return null;
        }

        return (BarlineInter) bars.get(reverse ? (bars.size() - 1) : 0);
    }

    //-----------//
    // lookupLeg //
    //-----------//
    /**
     * Look for a vertical leg on the desired side of the horizontal segment.
     *
     * @param seg     the horizontal segment
     * @param reverse the desired side
     * @param staff   related staff
     * @return the best seed found or null if none.
     */
    private Glyph lookupLeg (SegmentInfo seg,
                             boolean reverse,
                             StaffInfo staff)
    {
        Point end = seg.getEnd(reverse);
        Rectangle box = new Rectangle(end);
        box.grow(params.maxBarShift, 0);
        box.height = staff.getFirstLine().yAt(end.x) - end.y;

        SystemInfo system = staff.getSystem();
        Set<Section> sections = Sections.intersectedSections(box, system.getVerticalSections());
        Scale scale = sheet.getScale();
        FilamentsFactory factory = new FilamentsFactory(
                scale,
                sheet.getNest(),
                GlyphLayer.DEFAULT,
                VERTICAL,
                BasicGlyph.class);

        // Adjust factory parameters
        factory.setMaxThickness(
                (int) Math.ceil(sheet.getStemThickness() * constants.stemRatio.getValue()));
        factory.setMaxOverlapDeltaPos(constants.maxOverlapDeltaPos);
        factory.setMaxOverlapSpace(constants.maxOverlapSpace);
        factory.setMaxCoordGap(constants.maxCoordGap);

        if (system.getId() == 1) {
            factory.dump("EndingsBuilder factory");
        }

        // Retrieve candidates
        List<Glyph> glyphs = factory.retrieveFilaments(sections);

        if (glyphs.isEmpty()) {
            return null;
        }

        // Choose the seed whose top end is closest to segment end
        Glyph bestGlyph = null;
        double bestDistSq = Double.MAX_VALUE;

        for (Glyph glyph : glyphs) {
            Point2D top = glyph.getStartPoint(Orientation.VERTICAL);
            double dx = top.getX() - end.getX();
            double dy = top.getY() - end.getY();
            double distSq = (dx * dx) + (dy * dy);

            if ((bestGlyph == null) || (bestDistSq > distSq)) {
                bestDistSq = distSq;
                bestGlyph = glyph;
            }
        }

        return bestGlyph;
    }

    //----------------//
    // processSegment //
    //----------------//
    /**
     * Check the horizontal segment for being an ending.
     *
     * @param segment the horizontal segment
     */
    private void processSegment (SegmentInter segment)
    {
        // Check segment characteristics: length, slope, bar line alignments, legs.
        SegmentInfo seg = segment.getInfo();
        Point leftEnd = seg.getEnd(true);
        Point rightEnd = seg.getEnd(false);

        // Length
        double length = seg.getXLength();

        if (length < params.minLengthLow) {
            return;
        }

        // Slope
        Line2D line = new Line2D.Double(leftEnd, rightEnd);
        double slope = Math.abs(LineUtil.getSlope(line) - sheet.getSkew().getSlope());

        if (slope > params.maxSlope) {
            return;
        }

        // Relevant system(s)
        List<SystemInfo> systems = sheet.getSystemManager().getSystemsOf(leftEnd, null);
        systems.retainAll(sheet.getSystemManager().getSystemsOf(rightEnd, null));

        for (SystemInfo system : systems) {
            SIGraph sig = system.getSig();

            // Consider the staff just below the segment
            StaffInfo staff = system.getStaffBelow(leftEnd);

            if (staff == null) {
                continue;
            }

            List<Inter> systemBars = sig.inters(BarlineInter.class);

            // Left leg (mandatory)
            Glyph leftLeg = lookupLeg(seg, true, staff);

            if (leftLeg == null) {
                continue;
            }

            // Left bar (or DMZ)
            BarlineInter leftBar = lookupBar(seg, true, staff, systemBars);

            if (leftBar == null) {
                continue;
            }

            double leftDist = Math.abs(leftBar.getMedian().xAtY(leftEnd.y) - leftEnd.x);

            // Right leg (optional)
            Glyph rightLeg = lookupLeg(seg, false, staff);

            // Right bar
            BarlineInter rightBar = lookupBar(seg, false, staff, systemBars);

            if (rightBar == null) {
                continue;
            }

            double rightDist = Math.abs(rightBar.getMedian().xAtY(rightEnd.y) - rightEnd.x);

            // Create ending inter
            GradeImpacts segImp = segment.getImpacts();
            double straight = segImp.getGrade() / segImp.getIntrinsicRatio();

            GradeImpacts impacts = new EndingInter.Impacts(
                    straight,
                    1 - (slope / params.maxSlope),
                    (length - params.minLengthLow) / (params.minLengthHigh - params.minLengthLow),
                    1 - (leftDist / params.maxBarShift),
                    1 - (rightDist / params.maxBarShift));

            if (impacts.getGrade() >= EndingInter.getMinGrade()) {
                Line2D leftLine = new Line2D.Double(
                        leftLeg.getStartPoint(VERTICAL),
                        leftLeg.getStopPoint(VERTICAL));
                Line2D rightLine = (rightLeg == null) ? null
                        : new Line2D.Double(
                                rightLeg.getStartPoint(VERTICAL),
                                rightLeg.getStopPoint(VERTICAL));
                EndingInter endingInter = new EndingInter(
                        segment,
                        line,
                        leftLine,
                        rightLine,
                        segment.getBounds(),
                        impacts);
                sig.addVertex(endingInter);

                Scale scale = sheet.getScale();
                sig.addEdge(
                        endingInter,
                        leftBar,
                        new EndingBarRelation(LEFT, scale.pixelsToFrac(leftDist)));
                sig.addEdge(
                        endingInter,
                        rightBar,
                        new EndingBarRelation(RIGHT, scale.pixelsToFrac(rightDist)));
            }
        }
    }

    //~ Inner Classes ------------------------------------------------------------------------------
    //-----------//
    // Constants //
    //-----------//
    private static final class Constants
            extends ConstantSet
    {
        //~ Instance fields ------------------------------------------------------------------------

        final Scale.Fraction minLengthLow = new Scale.Fraction(6, "Low minimum ending length");

        final Scale.Fraction minLengthHigh = new Scale.Fraction(
                10,
                "High minimum ending length");

        final Scale.Fraction minLegLow = new Scale.Fraction(1.0, "Low minimum leg length");

        final Scale.Fraction minLegHigh = new Scale.Fraction(2.5, "High minimum leg length");

        final Scale.Fraction maxBarShift = new Scale.Fraction(
                2.0,
                "High maximum abscissa shift between ending and bar line");

        final Scale.Fraction maxLegShift = new Scale.Fraction(
                0.25,
                "High maximum abscissa shift between leg and bar line");

        final Scale.Fraction maxLegGap = new Scale.Fraction(
                0.5,
                "High maximum ordinate gap between leg and ending");

        final Constant.Double maxSlope = new Constant.Double(
                "tangent",
                0.02,
                "Maximum ending slope");

        final Constant.Ratio stemRatio = new Constant.Ratio(
                1.4,
                "Maximum leg thickness as ratio of stem thickness");

        final Scale.LineFraction maxOverlapDeltaPos = new Scale.LineFraction(
                1.0,
                "Maximum delta position between two overlapping filaments");

        final Scale.LineFraction maxOverlapSpace = new Scale.LineFraction(
                0.3,
                "Maximum space between overlapping filaments");

        final Scale.Fraction maxCoordGap = new Scale.Fraction(
                0.5,
                "Maximum delta coordinate for a gap between filaments");
    }

    //------------//
    // Parameters //
    //------------//
    /**
     * All pre-scaled constants.
     */
    private static class Parameters
    {
        //~ Instance fields ------------------------------------------------------------------------

        final double minLengthLow;

        final double minLengthHigh;

        final double minLegLow;

        final double minLegHigh;

        final int maxBarShift;

        final double maxLegShift;

        final double maxLegGap;

        final double maxSlope;

        //~ Constructors ---------------------------------------------------------------------------
        public Parameters (Scale scale)
        {
            minLengthLow = scale.toPixelsDouble(constants.minLengthLow);
            minLengthHigh = scale.toPixelsDouble(constants.minLengthHigh);
            minLegLow = scale.toPixelsDouble(constants.minLegLow);
            minLegHigh = scale.toPixelsDouble(constants.minLegHigh);
            maxBarShift = scale.toPixels(constants.maxBarShift);
            maxLegShift = scale.toPixelsDouble(constants.maxLegShift);
            maxLegGap = scale.toPixelsDouble(constants.maxLegGap);
            maxSlope = constants.maxSlope.getValue();

            if (logger.isDebugEnabled()) {
                Main.dumping.dump(this);
            }
        }
    }
}
