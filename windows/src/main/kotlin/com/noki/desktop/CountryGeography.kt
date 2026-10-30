package com.noki.desktop

import java.io.DataInputStream

/** Shared country geometry; packed at build time to avoid a temporary JSON object tree. */
internal class CountryGeography {
    val countries: Map<String, EarthCountryPoint>
    val outlines: Map<String, List<List<EarthCountryPoint>>>

    init {
        DataInputStream(checkNotNull(javaClass.getResourceAsStream("/noki/desktop/earth/countries.bin")).buffered()).use { data ->
            check(data.readInt() == 1) { "Unsupported country geometry" }
            fun count() = data.readInt().also { check(it in 0..100_000) { "Invalid country geometry size" } }
            fun point() = EarthCountryPoint(data.readFloat(), data.readFloat(), data.readFloat())
            countries = buildMap { repeat(count()) { put(data.readUTF(), point()) } }
            outlines = buildMap { repeat(count()) { put(data.readUTF(), List(count()) { List(count()) { point() } }) } }
            check(data.read() == -1) { "Unexpected country geometry data" }
        }
    }
}
