package com.milaboratory.mixcr.partialassembler;

import com.milaboratory.core.alignment.*;
import com.milaboratory.core.alignment.batch.AlignmentHit;
import com.milaboratory.core.alignment.batch.AlignmentResult;
import com.milaboratory.core.alignment.batch.BatchAlignerWithBaseWithFilter;
import com.milaboratory.core.alignment.kaligner1.AbstractKAlignerParameters;
import com.milaboratory.core.alignment.kaligner1.KAlignerParameters;
import com.milaboratory.core.alignment.kaligner2.KAlignerParameters2;
import com.milaboratory.core.sequence.NSequenceWithQuality;
import com.milaboratory.core.sequence.NucleotideSequence;
import com.milaboratory.mixcr.basictypes.VDJCAlignments;
import com.milaboratory.mixcr.basictypes.VDJCHit;
import com.milaboratory.mixcr.vdjaligners.VDJCAlignerAbstract;
import com.milaboratory.mixcr.vdjaligners.VDJCAlignerParameters;
import com.milaboratory.mixcr.vdjaligners.VDJCAlignmentResult;
import io.repseq.core.Chains;
import io.repseq.core.GeneType;
import io.repseq.core.ReferencePoint;
import io.repseq.core.VDJCGene;

import java.util.Arrays;
import java.util.EnumMap;

import static com.milaboratory.mixcr.vdjaligners.VDJCAlignerPVFirst.combine;

/**
 * @author Dmitry Bolotin
 * @author Stanislav Poslavsky
 */
public final class PartialAlignmentsAssemblerAligner extends VDJCAlignerAbstract<VDJCMultiRead> {
    private final ThreadLocal<AlignerCustom.LinearMatrixCache> linearMatrixCache = new ThreadLocal<AlignerCustom.LinearMatrixCache>() {
        @Override
        protected AlignerCustom.LinearMatrixCache initialValue() {
            return new AlignerCustom.LinearMatrixCache();
        }
    };

    public PartialAlignmentsAssemblerAligner(VDJCAlignerParameters parameters) {
        super(parameters);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected VDJCAlignmentResult<VDJCMultiRead> process0(VDJCMultiRead input) {
        ensureInitialized();

        final int nReads = input.numberOfReads();
        EnumMap<GeneType, VDJCHit[]> vdjcHits = new EnumMap<>(GeneType.class);

        NSequenceWithQuality[] targets = new NSequenceWithQuality[nReads];

        Chains currentChains = Chains.ALL;

        for (int g = 0; g < GeneType.VJC_REFERENCE.length; g++) {
            GeneType gt = GeneType.VJC_REFERENCE[g];
            AlignmentHit<NucleotideSequence, VDJCGene>[][] alignmentHits = new AlignmentHit[nReads][];
            for (int i = 0; i < nReads; i++) {
                alignmentHits[i] = new AlignmentHit[0];

                targets[i] = input.getRead(i).getData();

                final NucleotideSequence sequence = input.getRead(i).getData().getSequence();

                AlignmentResult<AlignmentHit<NucleotideSequence, VDJCGene>> als;

                final BatchAlignerWithBaseWithFilter<NucleotideSequence, VDJCGene,
                        AlignmentHit<NucleotideSequence, VDJCGene>>
                        aligner = getAligner(gt);
                if (aligner != null) {
                    int pointer = 0;
                    if (g != 0) { // Not V gene
                        VDJCHit[] vdjcHits1 = vdjcHits.get(GeneType.VJC_REFERENCE[g - 1]);
                        Alignment<NucleotideSequence> alignment;
                        if (vdjcHits1.length != 0 && (alignment = vdjcHits1[0].getAlignment(i)) != null)
                            pointer = alignment.getSequence2Range().getTo();
                    }
                    als = aligner.align(sequence, pointer, sequence.size(), getFilter(gt, currentChains));
                    if (als != null && als.hasHits())
                        alignmentHits[i] = als.getHits().toArray(new AlignmentHit[als.getHits().size()]);
                }
            }

            Chains chains = Chains.EMPTY;
            for (AlignmentHit<NucleotideSequence, VDJCGene>[] alignmentHit0 : alignmentHits)
                if (alignmentHit0 != null)
                    for (AlignmentHit<NucleotideSequence, VDJCGene> hit : alignmentHit0)
                        chains = chains.merge(hit.getRecordPayload().getChains());
            currentChains = currentChains.intersection(chains);

            vdjcHits.put(gt, combine(parameters.getFeatureToAlign(gt), alignmentHits));
        }

        VDJCHit[] vHits = vdjcHits.get(GeneType.Variable);
        final AlignmentScoring<NucleotideSequence> vScoring = parameters.getVAlignerParameters().getParameters().getScoring();
        if (vHits != null && vHits.length > 0 && !(vScoring instanceof AffineGapAlignmentScoring)) { // TODO implement AffineGapAlignmentScoring
            int minimalVSpace = getAbsoluteMinScore(parameters.getVAlignerParameters().getParameters()) /
                    parameters.getVAlignerParameters().getParameters().getScoring().getMaximalMatchScore();

            for (int targetId = 1; targetId < nReads; targetId++) {
                int vSpace;
                if (vdjcHits.get(GeneType.Joining) != null && vdjcHits.get(GeneType.Joining).length > 0 &&
                        vdjcHits.get(GeneType.Joining)[0].getAlignment(targetId) != null &&
                        (vSpace = vdjcHits.get(GeneType.Joining)[0].getAlignment(targetId).getSequence2Range().getFrom()) > minimalVSpace) {
                    for (int vHitIndex = 0; vHitIndex < vHits.length; vHitIndex++) {
                        VDJCHit vHit = vHits[vHitIndex];
                        Alignment<NucleotideSequence> leftAlignment = vHit.getAlignment(targetId - 1);
                        if (leftAlignment == null)
                            continue;

                        final NucleotideSequence sequence1 = leftAlignment.getSequence1();
                        final NucleotideSequence sequence2 = targets[targetId].getSequence();

                        final int beginFR3 = vHit.getGene().getPartitioning().getRelativePosition(parameters.getFeatureToAlign(GeneType.Variable), ReferencePoint.FR3Begin);
                        if (beginFR3 == -1)
                            continue;

                        final Alignment alignment = AlignerCustom.alignLinearSemiLocalLeft0(
                                (LinearGapAlignmentScoring<NucleotideSequence>) vScoring,
                                sequence1, sequence2,
                                beginFR3, sequence1.size() - beginFR3,
                                0, vSpace,
                                false, true,
                                NucleotideSequence.ALPHABET,
                                linearMatrixCache.get());

                        if (alignment.getScore() < getAbsoluteMinScore(parameters.getVAlignerParameters().getParameters()))
                            continue;

                        vHits[vHitIndex] = vHit.setAlignment(targetId, alignment);
                    }
                }
            }
        }
        Arrays.sort(vHits);


        int dGeneTarget = -1;
        VDJCHit[] vResult = vdjcHits.get(GeneType.Variable);
        VDJCHit[] jResult = vdjcHits.get(GeneType.Joining);
        if (vResult.length != 0 && jResult.length != 0)
            for (int i = 0; i < nReads; i++)
                if (vResult[0].getAlignment(i) != null && jResult[0].getAlignment(i) != null) {
                    dGeneTarget = i;
                    break;
                }

        VDJCHit[] dResult;
        if (dGeneTarget == -1)
            dResult = new VDJCHit[0];
        else {
            final Alignment<NucleotideSequence> vAl = vResult[0].getAlignment(dGeneTarget);
            final Alignment<NucleotideSequence> jAl = jResult[0].getAlignment(dGeneTarget);
            if (vAl == null || jAl == null || singleDAligner == null)
                dResult = new VDJCHit[0];
            else
                dResult = singleDAligner.align(targets[dGeneTarget].getSequence(), getPossibleDLoci(vResult, jResult),
                        vAl.getSequence2Range().getTo(),
                        jAl.getSequence2Range().getFrom(),
                        dGeneTarget, nReads);
        }

        final VDJCAlignments alignment = new VDJCAlignments(input.getId(),
                vResult,
                dResult,
                jResult,
                vdjcHits.get(GeneType.Constant),
                targets
        );
        return new VDJCAlignmentResult<>(input, alignment);
    }

    private static int getAbsoluteMinScore(AbstractKAlignerParameters kParameters) {
        if (kParameters instanceof KAlignerParameters)
            return (int) ((KAlignerParameters) kParameters).getAbsoluteMinScore();
        else if (kParameters instanceof KAlignerParameters2)
            return ((KAlignerParameters2) kParameters).getAbsoluteMinScore();
        else
            throw new RuntimeException("Not supported scoring type.");
    }
}
