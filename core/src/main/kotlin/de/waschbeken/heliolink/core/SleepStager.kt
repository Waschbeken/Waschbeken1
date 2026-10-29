package de.waschbeken.heliolink.core

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Estimates sleep stages from the per-minute samples (heart rate and movement) for nights where the
 * strap did not deliver its own stage records.
 *
 * The decision tree was trained on 110 nights of a Helio Strap with the strap's own stages as
 * reference (leave-nights-out accuracy about 69 %, light sleep only would be 52 %). It is an
 * estimate, so the app marks such nights as "geschätzt".
 */
object SleepStager {
    class Features(
        val minute: Double,
        val frac: Double,
        val toEnd: Double,
        val sleepkind: Double,
        val hrRel: Double,
        val hrM5: Double,
        val hrM15: Double,
        val hrSd5: Double,
        val hrSd15: Double,
        val hrDiff: Double,
        val hrB60: Double,
        val in5: Double,
        val in15: Double,
        val in31: Double,
        val in61: Double,
        val still: Double,
    )

    /** Stages for consecutive minutes of one night (smoothed over 7 minutes). */
    fun stages(samples: List<ActivitySample>): List<SleepStage> {
        if (samples.isEmpty()) return emptyList()
        val raw = features(samples).map { classify(it) }
        return smooth(raw)
    }

    fun features(samples: List<ActivitySample>): List<Features> {
        val n = samples.size
        val hr = interpolate(DoubleArray(n) { i ->
            val v = samples[i].heartRate
            if (v <= 0 || v >= 255) Double.NaN else v.toDouble()
        })
        val inten = DoubleArray(n) { i -> samples[i].intensity.toDouble() }
        val median = median(hr.filter { !it.isNaN() }).takeIf { !it.isNaN() && it > 0 } ?: 1.0
        val diff = DoubleArray(n) { i -> if (i == 0 || hr[i].isNaN() || hr[i - 1].isNaN()) Double.NaN else abs(hr[i] - hr[i - 1]) }
        return List(n) { i ->
            Features(
                minute = i.toDouble(),
                frac = if (n > 1) i.toDouble() / (n - 1) else 0.0,
                toEnd = (n - 1 - i).toDouble(),
                sleepkind = if (samples[i].rawKind == ActivitySample.KIND_SLEEP) 1.0 else 0.0,
                hrRel = hr[i] / median,
                hrM5 = mean(hr, i, 5) / median,
                hrM15 = mean(hr, i, 15) / median,
                hrSd5 = std(hr, i, 5),
                hrSd15 = std(hr, i, 15),
                hrDiff = mean(diff, i, 5).let { if (it.isNaN()) 0.0 else it },
                hrB60 = hr[i] / median(window(hr, i, 61).filter { !it.isNaN() }),
                in5 = mean(inten, i, 5),
                in15 = mean(inten, i, 15),
                in31 = mean(inten, i, 31),
                in61 = mean(inten, i, 61),
                still = window(inten, i, 11).maxOrNull() ?: 0.0,
            )
        }
    }

    /** Linear interpolation of missing values; the ends take the nearest value. */
    private fun interpolate(v: DoubleArray): DoubleArray {
        val known = v.indices.filter { !v[it].isNaN() }
        if (known.isEmpty()) return v
        val out = v.copyOf()
        for (i in v.indices) {
            if (!out[i].isNaN()) continue
            val before = known.lastOrNull { it < i }
            val after = known.firstOrNull { it > i }
            out[i] = when {
                before == null -> v[after!!]
                after == null -> v[before]
                else -> v[before] + (v[after] - v[before]) * (i - before) / (after - before)
            }
        }
        return out
    }

    private fun window(v: DoubleArray, i: Int, w: Int): List<Double> {
        val h = w / 2
        return (maxOf(0, i - h)..minOf(v.size - 1, i + h)).map { v[it] }
    }

    private fun mean(v: DoubleArray, i: Int, w: Int): Double {
        val values = window(v, i, w).filter { !it.isNaN() }
        return if (values.isEmpty()) Double.NaN else values.average()
    }

    /** Sample standard deviation like pandas (ddof = 1), 0 with fewer than two values. */
    private fun std(v: DoubleArray, i: Int, w: Int): Double {
        val values = window(v, i, w).filter { !it.isNaN() }
        if (values.size < 2) return 0.0
        val m = values.average()
        return sqrt(values.sumOf { (it - m) * (it - m) } / (values.size - 1))
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return Double.NaN
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    /** Most frequent stage in a centred 7 minute window; ties go to the lower stage code like in training. */
    private fun smooth(stages: List<SleepStage>): List<SleepStage> {
        val order = listOf(SleepStage.LIGHT, SleepStage.DEEP, SleepStage.AWAKE, SleepStage.REM) // codes 4, 5, 7, 8
        return stages.indices.map { i ->
            val window = (maxOf(0, i - 3)..minOf(stages.size - 1, i + 3)).map { stages[it] }
            val counts = window.groupingBy { it }.eachCount()
            val max = counts.values.max()
            order.first { counts[it] == max }
        }
    }

    @Suppress("CyclomaticComplexMethod")
    fun classify(f: Features): SleepStage {
        return run {
        if (f.minute <= 65.500000) {
            if (f.minute <= 8.500000) {
                if (f.minute <= 5.500000) {
                    SleepStage.LIGHT
                } else {
                    if (f.still <= 0.500000) {
                        if (f.hrDiff <= 0.500000) {
                            if (f.frac <= 0.015915) {
                                SleepStage.LIGHT
                            } else {
                                SleepStage.DEEP
                            }
                        } else {
                            SleepStage.LIGHT
                        }
                    } else {
                        SleepStage.LIGHT
                    }
                }
            } else {
                if (f.still <= 1.500000) {
                    if (f.hrSd15 <= 1.845199) {
                        if (f.minute <= 11.500000) {
                            SleepStage.DEEP
                        } else {
                            if (f.in31 <= 2.212903) {
                                SleepStage.DEEP
                            } else {
                                if (f.minute <= 49.500000) {
                                    SleepStage.DEEP
                                } else {
                                    if (f.hrSd15 <= 0.891725) {
                                        SleepStage.DEEP
                                    } else {
                                        SleepStage.LIGHT
                                    }
                                }
                            }
                        }
                    } else {
                        if (f.in31 <= 0.327957) {
                            SleepStage.DEEP
                        } else {
                            if (f.hrB60 <= 0.988094) {
                                SleepStage.DEEP
                            } else {
                                if (f.hrSd15 <= 2.708860) {
                                    if (f.frac <= 0.066581) {
                                        SleepStage.DEEP
                                    } else {
                                        SleepStage.LIGHT
                                    }
                                } else {
                                    SleepStage.LIGHT
                                }
                            }
                        }
                    }
                } else {
                    if (f.hrDiff <= 0.900000) {
                        if (f.in61 <= 3.414869) {
                            if (f.hrDiff <= 0.300000) {
                                SleepStage.DEEP
                            } else {
                                if (f.in31 <= 1.305161) {
                                    SleepStage.DEEP
                                } else {
                                    if (f.hrB60 <= 0.994845) {
                                        SleepStage.DEEP
                                    } else {
                                        SleepStage.LIGHT
                                    }
                                }
                            }
                        } else {
                            if (f.hrB60 <= 0.988500) {
                                SleepStage.DEEP
                            } else {
                                SleepStage.LIGHT
                            }
                        }
                    } else {
                        if (f.hrM5 <= 1.286527) {
                            if (f.in15 <= 4.166667) {
                                if (f.in31 <= 0.644009) {
                                    SleepStage.DEEP
                                } else {
                                    if (f.in5 <= 4.300000) {
                                        SleepStage.LIGHT
                                    } else {
                                        SleepStage.DEEP
                                    }
                                }
                            } else {
                                if (f.hrB60 <= 1.072817) {
                                    if (f.in61 <= 7.390965) {
                                        SleepStage.LIGHT
                                    } else {
                                        SleepStage.AWAKE
                                    }
                                } else {
                                    SleepStage.AWAKE
                                }
                            }
                        } else {
                            SleepStage.AWAKE
                        }
                    }
                }
            }
        } else {
            if (f.hrDiff <= 0.775000) {
                if (f.hrSd15 <= 0.839482) {
                    if (f.minute <= 219.500000) {
                        if (f.still <= 0.500000) {
                            SleepStage.DEEP
                        } else {
                            if (f.hrM15 <= 1.048566) {
                                SleepStage.LIGHT
                            } else {
                                SleepStage.REM
                            }
                        }
                    } else {
                        if (f.in61 <= 1.844262) {
                            if (f.toEnd <= 56.500000) {
                                SleepStage.LIGHT
                            } else {
                                if (f.frac <= 0.572770) {
                                    SleepStage.LIGHT
                                } else {
                                    if (f.hrRel <= 0.875397) {
                                        SleepStage.LIGHT
                                    } else {
                                        SleepStage.DEEP
                                    }
                                }
                            }
                        } else {
                            if (f.in31 <= 0.048387) {
                                if (f.minute <= 267.500000) {
                                    SleepStage.LIGHT
                                } else {
                                    if (f.hrM5 <= 0.955048) {
                                        SleepStage.DEEP
                                    } else {
                                        SleepStage.LIGHT
                                    }
                                }
                            } else {
                                SleepStage.LIGHT
                            }
                        }
                    }
                } else {
                    if (f.hrB60 <= 1.023953) {
                        if (f.in61 <= 0.924133) {
                            if (f.minute <= 182.500000) {
                                if (f.hrB60 <= 0.994382) {
                                    SleepStage.DEEP
                                } else {
                                    if (f.hrDiff <= 0.300000) {
                                        SleepStage.DEEP
                                    } else {
                                        SleepStage.LIGHT
                                    }
                                }
                            } else {
                                if (f.in31 <= 0.145161) {
                                    SleepStage.REM
                                } else {
                                    SleepStage.LIGHT
                                }
                            }
                        } else {
                            if (f.hrDiff <= 0.266667) {
                                if (f.in15 <= 1.900000) {
                                    if (f.in31 <= 0.080645) {
                                        SleepStage.DEEP
                                    } else {
                                        SleepStage.LIGHT
                                    }
                                } else {
                                    if (f.in61 <= 2.188525) {
                                        SleepStage.DEEP
                                    } else {
                                        SleepStage.LIGHT
                                    }
                                }
                            } else {
                                SleepStage.LIGHT
                            }
                        }
                    } else {
                        if (f.in15 <= 1.566667) {
                            if (f.in61 <= 3.081967) {
                                if (f.hrRel <= 1.028992) {
                                    if (f.hrB60 <= 1.033060) {
                                        SleepStage.LIGHT
                                    } else {
                                        SleepStage.REM
                                    }
                                } else {
                                    SleepStage.REM
                                }
                            } else {
                                SleepStage.LIGHT
                            }
                        } else {
                            if (f.hrM5 <= 1.024356) {
                                SleepStage.LIGHT
                            } else {
                                if (f.minute <= 199.000000) {
                                    if (f.in61 <= 2.008197) {
                                        SleepStage.LIGHT
                                    } else {
                                        SleepStage.REM
                                    }
                                } else {
                                    SleepStage.REM
                                }
                            }
                        }
                    }
                }
            } else {
                if (f.still <= 17.500000) {
                    if (f.hrDiff <= 1.366667) {
                        if (f.hrB60 <= 1.013246) {
                            if (f.in31 <= 0.693548) {
                                if (f.hrSd15 <= 1.140158) {
                                    if (f.in31 <= 0.016129) {
                                        SleepStage.DEEP
                                    } else {
                                        SleepStage.LIGHT
                                    }
                                } else {
                                    if (f.toEnd <= 369.000000) {
                                        SleepStage.REM
                                    } else {
                                        SleepStage.LIGHT
                                    }
                                }
                            } else {
                                SleepStage.LIGHT
                            }
                        } else {
                            if (f.in15 <= 2.700000) {
                                if (f.hrM5 <= 0.973234) {
                                    SleepStage.LIGHT
                                } else {
                                    SleepStage.REM
                                }
                            } else {
                                if (f.in61 <= 2.450820) {
                                    SleepStage.LIGHT
                                } else {
                                    if (f.in5 <= 0.100000) {
                                        SleepStage.LIGHT
                                    } else {
                                        SleepStage.REM
                                    }
                                }
                            }
                        }
                    } else {
                        if (f.still <= 7.500000) {
                            if (f.in31 <= 4.016129) {
                                if (f.in15 <= 0.300000) {
                                    if (f.in61 <= 6.049180) {
                                        SleepStage.REM
                                    } else {
                                        SleepStage.LIGHT
                                    }
                                } else {
                                    SleepStage.REM
                                }
                            } else {
                                SleepStage.LIGHT
                            }
                        } else {
                            if (f.hrM15 <= 1.011355) {
                                if (f.in5 <= 3.100000) {
                                    if (f.in31 <= 4.397252) {
                                        SleepStage.REM
                                    } else {
                                        SleepStage.LIGHT
                                    }
                                } else {
                                    SleepStage.LIGHT
                                }
                            } else {
                                if (f.in61 <= 1.549180) {
                                    if (f.hrM15 <= 1.030807) {
                                        SleepStage.LIGHT
                                    } else {
                                        SleepStage.REM
                                    }
                                } else {
                                    if (f.in61 <= 4.401639) {
                                        SleepStage.REM
                                    } else {
                                        SleepStage.LIGHT
                                    }
                                }
                            }
                        }
                    }
                } else {
                    if (f.in5 <= 12.100000) {
                        if (f.in15 <= 3.544444) {
                            if (f.hrM15 <= 0.998860) {
                                if (f.hrSd15 <= 1.917338) {
                                    SleepStage.LIGHT
                                } else {
                                    if (f.hrB60 <= 0.945942) {
                                        SleepStage.REM
                                    } else {
                                        SleepStage.LIGHT
                                    }
                                }
                            } else {
                                if (f.minute <= 186.500000) {
                                    SleepStage.LIGHT
                                } else {
                                    if (f.still <= 23.500000) {
                                        SleepStage.REM
                                    } else {
                                        SleepStage.LIGHT
                                    }
                                }
                            }
                        } else {
                            if (f.hrM5 <= 0.998795) {
                                SleepStage.LIGHT
                            } else {
                                if (f.sleepkind <= 0.500000) {
                                    if (f.in5 <= 3.100000) {
                                        SleepStage.LIGHT
                                    } else {
                                        SleepStage.AWAKE
                                    }
                                } else {
                                    SleepStage.LIGHT
                                }
                            }
                        }
                    } else {
                        if (f.hrM5 <= 1.162785) {
                            if (f.hrB60 <= 1.020842) {
                                SleepStage.LIGHT
                            } else {
                                if (f.frac <= 0.988978) {
                                    if (f.hrSd15 <= 4.188988) {
                                        SleepStage.LIGHT
                                    } else {
                                        SleepStage.AWAKE
                                    }
                                } else {
                                    SleepStage.LIGHT
                                }
                            }
                        } else {
                            SleepStage.AWAKE
                        }
                    }
                }
            }
        }
        }
    }
}
