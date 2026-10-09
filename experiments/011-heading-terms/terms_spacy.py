"""Terms from book headings with spaCy, beside the rule in workshop.books/words.

Reads headings.json from the books cache (written from Clojure, so both
sides see the same headings and the rule's own words), and writes
spacy-terms.json there. Prints the comparison. No book text is read: a
heading is all either side gets.

Run: uv run --with spacy --with 'en_core_web_sm @ <wheel url in README>' python terms_spacy.py
"""
import collections
import itertools
import json
import os
import pathlib
import re

import spacy

cache_dir = pathlib.Path(os.environ.get("BOOKS_CACHE")
                         or pathlib.Path(os.environ.get("XDG_CACHE_HOME") or pathlib.Path.home() / ".cache") / "workshop-books")
source = json.loads((cache_dir / "headings.json").read_text())
headings = source["headings"]
nlp = spacy.load("en_core_web_sm", disable=["ner"])

NUMBERING = re.compile(r"^\s*(chapter|part|appendix)?\s*[\dA-Z]*[\d.]+[ .:—-]*", re.IGNORECASE)


def spacy_terms(doc):
    """Single lemmas of nouns and proper nouns, and noun chunks of two or three words."""
    singles = {token.lemma_.lower() for token in doc
               if token.pos_ in ("NOUN", "PROPN") and token.is_alpha and not token.is_stop and len(token) >= 4}
    phrases = set()
    for chunk in doc.noun_chunks:
        kept = [token.lemma_.lower() for token in chunk if not token.is_stop and token.is_alpha]
        if 2 <= len(kept) <= 3:
            phrases.add(" ".join(kept))
    return singles, phrases


titles = [NUMBERING.sub("", heading["title"]) for heading in headings]
by_book_rule = collections.defaultdict(set)
by_book_single = collections.defaultdict(set)
by_book_phrase = collections.defaultdict(set)
per_heading = []
for heading, doc in zip(headings, nlp.pipe(titles, batch_size=256)):
    singles, phrases = spacy_terms(doc)
    by_book_rule[heading["book"]].update(heading["words"])
    by_book_single[heading["book"]].update(singles)
    by_book_phrase[heading["book"]].update(phrases)
    per_heading.append({"id": heading["id"], "singles": sorted(singles), "phrases": sorted(phrases)})


def shared(by_book, minimum=2):
    counts = collections.Counter(term for terms in by_book.values() for term in terms)
    return {term for term, books in counts.items() if books >= minimum}


def similar_pairs(by_book, vocabulary, top=10):
    rows = []
    for first, second in itertools.combinations(sorted(by_book), 2):
        a, b = by_book[first] & vocabulary, by_book[second] & vocabulary
        if a | b:
            rows.append((len(a & b) / len(a | b), first, second))
    return sorted(rows, reverse=True)[:top]


rule_terms, single_terms, phrase_terms = shared(by_book_rule), shared(by_book_single), shared(by_book_phrase)
print(f"headings {len(headings)}, books {len(by_book_rule)}")
print(f"terms in two or more books: rule {len(rule_terms)}, spaCy single {len(single_terms)}, spaCy phrases {len(phrase_terms)}")
print(f"single terms in both {len(rule_terms & single_terms)}, rule only {len(rule_terms - single_terms)}, spaCy only {len(single_terms - rule_terms)}")
print("rule only, sample:", sorted(rule_terms - single_terms)[:40])
print("spaCy only, sample:", sorted(single_terms - rule_terms)[:40])
phrase_books = collections.Counter(term for terms in by_book_phrase.values() for term in terms)
print("phrases by number of books:", [(term, count) for term, count in phrase_books.most_common(40)])

rule_pairs = similar_pairs(by_book_rule, rule_terms)
single_pairs = similar_pairs(by_book_single, single_terms)
both_pairs = similar_pairs({book: by_book_single[book] | by_book_phrase[book] for book in by_book_single},
                           single_terms | phrase_terms)
as_set = lambda pairs: {(first, second) for _, first, second in pairs}
print("top 10 pairs, rule:")
for score, first, second in rule_pairs:
    print(f"  {score:.3f} {first} ~ {second}")
print("top 10 pairs, spaCy singles + phrases:")
for score, first, second in both_pairs:
    print(f"  {score:.3f} {first} ~ {second}")
print(f"top-10 overlap: rule vs spaCy singles {len(as_set(rule_pairs) & as_set(single_pairs))}, rule vs singles+phrases {len(as_set(rule_pairs) & as_set(both_pairs))}")

(cache_dir / "spacy-terms.json").write_text(json.dumps(
    {"single_terms": sorted(single_terms), "phrase_terms": sorted(phrase_terms), "headings": per_heading}))
