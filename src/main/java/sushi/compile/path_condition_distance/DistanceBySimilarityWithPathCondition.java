package sushi.compile.path_condition_distance;

import java.util.List;
import java.util.Map;

import sushi.logging.Logger;

public class DistanceBySimilarityWithPathCondition {
    private static final Logger logger = new Logger(DistanceBySimilarityWithPathCondition.class);

    public static double distance(List<ClauseSimilarityHandler> pathConditionHandler, Map<String, Object> candidateObjects, Map<Long, String> constants, ClassLoader classLoader) {
        return distance(pathConditionHandler, candidateObjects, constants, classLoader, null/*no-caching behavior*/); 
    }


    public static double distance(List<ClauseSimilarityHandler> pathConditionSimilarityHandlers, Map<String, Object> candidateObjects, Map<Long, String> constants, ClassLoader classLoader, SushiLibCache cache) {
        logger.debug("Computing similarity with path condition: ");

        double achievedSimilarity = 0.0d;		
        CandidateBackbone backbone = CandidateBackbone.makeNewBackbone(classLoader); 
        for (ClauseSimilarityHandler handler : pathConditionSimilarityHandlers) {
            achievedSimilarity += handler.evaluateSimilarity(backbone, candidateObjects, constants, cache);
        }

        logger.debug("Similarity with path condition is " + achievedSimilarity);

        final double goalSimilarity = pathConditionSimilarityHandlers.size();
        final double distance = goalSimilarity - achievedSimilarity;
        assert (distance >= 0);

        logger.debug("Distance from path condition is " + distance);

        return distance;
    }
}
