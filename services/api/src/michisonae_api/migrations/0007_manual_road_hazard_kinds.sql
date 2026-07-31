ALTER TABLE public.road_observations
    DROP CONSTRAINT road_observations_kind_check,
    ADD CONSTRAINT road_observations_kind_check CHECK (
        kind IN (
            'road_damage',
            'rough_road',
            'obstruction',
            'flooding',
            'manhole_hazard',
            'road_construction',
            'disabled_vehicle'
        )
    );

ALTER TABLE public.hazard_clusters
    DROP CONSTRAINT hazard_clusters_kind_check,
    ADD CONSTRAINT hazard_clusters_kind_check CHECK (
        kind IN (
            'road_damage',
            'rough_road',
            'obstruction',
            'flooding',
            'manhole_hazard',
            'road_construction',
            'disabled_vehicle'
        )
    );

ALTER TABLE public.hazard_projections
    DROP CONSTRAINT hazard_projections_kind_check,
    ADD CONSTRAINT hazard_projections_kind_check CHECK (
        kind IN (
            'road_damage',
            'rough_road',
            'obstruction',
            'flooding',
            'manhole_hazard',
            'road_construction',
            'disabled_vehicle'
        )
    );
