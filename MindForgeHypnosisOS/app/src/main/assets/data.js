'use strict';
const $=id=>document.getElementById(id); const clamp=(v,a,b)=>Math.min(b,Math.max(a,v)); const clean=s=>(s||'').trim().replace(/\s+/g,' '); const esc=s=>String(s??'').replace(/[&<>"']/g,m=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[m]));
const STORE={programs:'mf2_programs',history:'mf2_history',settings:'mf2_settings'};
const INDUCTIONS={
 breath:{name:'Breath Pacing',text:'Let the breath become the metronome. Inhale gently. Exhale longer. Each exhale releases unnecessary effort. Attention narrows to breath, voice, and the next useful image.'},
 eye:{name:'Eye Fixation + Closure',text:'Choose one point and let the eyes rest there. Notice tiny changes in focus, blinking, and heaviness. When the eyes prefer to close, allow them to close and let that shift mark the beginning of deeper inward attention.'},
 pmr:{name:'Progressive Relaxation',text:'Move attention from forehead to jaw, shoulders, hands, chest, abdomen, legs, and feet. At each area, briefly notice tension, then let the muscles soften while keeping the mind alert enough to follow the chosen program.'},
 body:{name:'Body Scan Absorption',text:'Scan slowly through sensation. Notice temperature, pressure, weight, and contact. Let each sensation become more vivid while outside details become less important.'},
 countdown:{name:'Rapid Countdown',text:'Take one settling breath. Count from five to one, and with each number narrow attention more sharply. Five. Four. Three. Two. One. At one, let the next suggestion become the main object of awareness.'},
 fraction:{name:'Fractionation',text:'Open the eyes briefly, then close them and notice how quickly inward focus returns. Repeat that shift two more times, each time allowing the return inward to become faster and more familiar.'},
 confusion:{name:'Pattern Confusion',text:'Notice how attention can follow one thought, then another, then the space between them. You do not need to solve the sequence. Let the effort to track everything dissolve into simple listening and imagery.'},
 sensory:{name:'5-4-3-2-1 Sensory Narrowing',text:'Notice five visual details, then four body sensations, three sounds, two breaths, and one central intention. Let the field of attention become smaller and more deliberate with each step.'},
 selfElman:{name:'Self-Elman Style',text:'Close the eyes and deliberately relax the muscles around them. Let that relaxation spread downward. Imagine testing the eyelids and discovering that relaxation is easier than effort. Then count three numbers downward and let each number double the inward focus.'},
 instant:{name:'Cue-Linked Rapid Entry',text:'Take the chosen cue now. Pair it with one slow exhale and the phrase: inward now. Rehearse the cue three times, each repetition shortening the path from ordinary attention to focused inward attention.'}
};
const DEEPENERS={
 stairs:{name:'Staircase Descent',text:'Imagine ten steps descending to a quiet training chamber. With each step, outside concerns become less relevant and the chosen program becomes clearer. Ten down to one.'},
 count:{name:'10→1 Countdown',text:'Count slowly from ten to one. With every number, let attention become narrower, imagery more vivid, and the next useful suggestion easier to rehearse.'},
 elevator:{name:'Elevator Levels',text:'Imagine an elevator moving through levels ten to one. At each level, the body becomes quieter and the target program becomes more available.'},
 time:{name:'Time Distortion',text:'Imagine that a few minutes of clock time can contain a much larger amount of inner rehearsal. Let each breath hold enough subjective space for another clean repetition.'},
 autogenic:{name:'Warmth + Heaviness',text:'Imagine comfortable warmth in the hands and comfortable heaviness in the limbs. Let those sensations become signals that the body can settle while attention remains directed.'},
 beach:{name:'Sensory Scene',text:'Enter a vivid place with depth, temperature, sound, texture, and distance. Every sensory detail becomes another layer of absorption.'},
 spiralDeep:{name:'Spiral Descent',text:'Imagine attention moving inward in a slow spiral, each circle smaller, quieter, and more precise until the chosen suggestion sits at the center.'},
 fractionDeep:{name:'Deep Fractionation',text:'Move slightly toward alertness for one breath, then drop inward twice as quickly. Repeat this wave three times and let the contrast make the inward state more distinct.'},
 blank:{name:'Blank-Space Deepener',text:'Notice the small quiet gaps between words and between breaths. Let those gaps widen subjectively until they feel spacious enough for imagery and suggestion to land without effort.'}
};
const STYLES={
 'Direct command':(g,c,cx)=>`Install this cleanly now: ${g} Whenever ${c}, this response becomes easier to express in ${cx}.`,
 'Permissive Ericksonian':(g,c,cx)=>`A deeper part of you can begin discovering how naturally ${g.toLowerCase()} especially whenever ${c} in ${cx}.`,
 'First-person affirmation':(g,c,cx)=>`I choose this pattern: ${g} When ${c}, I remember it, feel it, and act from it in ${cx}.`,
 'Implementation intention':(g,c,cx)=>`If or when ${c}, then I immediately begin this response: ${g} This applies especially in ${cx}.`,
 'Mental rehearsal':(g,c,cx)=>`Rehearse one precise repetition in ${cx}. The cue appears: ${c}. You notice it, then ${g.toLowerCase()} through timing, posture, attention, and choice.`,
 'Sensory imagery':(g,c,cx)=>`See, hear, and feel the result already happening: ${g} Let ${c} become the sensory doorway to that state in ${cx}.`,
 'Identity installation':(g,c,cx)=>`This becomes increasingly characteristic of who you are in action: ${g} The cue ${c} reminds you of that identity in ${cx}.`,
 'Question loop':(g,c,cx)=>`What changes first as ${g.toLowerCase()}? How quickly does ${c} make the useful response obvious in ${cx}?`,
 'Contrast frame':(g,c,cx)=>`Notice the old pattern as distant and unnecessary, then make the new pattern vivid and immediate: ${g} Let ${c} mark the switch in ${cx}.`,
 'Symbolic archetype':(g,c,cx)=>`Let the inner forge shape this program into symbol, instinct, posture, breath, and action: ${g} The activation cue is ${c}.`,
 'Layered repetition':(g,c,cx)=>`${g} Let the words become an image, the image become a feeling, and the feeling become an automatic next action whenever ${c} in ${cx}.`
};
const PRESETS={
 focus:{name:'Absolute Focus',emoji:'◉',desc:'Attention lock + rapid cue',goal:'I enter deep focus fast, stay locked onto the mission, and act with calm precision.',evidence:'I notice distractions without following them and return instantly to the next useful action.',cue:'I take one slow inhale and lightly press thumb to finger',context:'training, studying, creating, or performing',induction:'breath',deepener:'count',style:'Implementation intention',style2:'Direct command',audio:'binaural',visual:'breathe',beat:6,reps:8,ending:'Wake energized'},
 confidence:{name:'Calm Dominance',emoji:'◆',desc:'Composure + decisive action',goal:'I move, speak, and decide from calm unshakable confidence.',evidence:'My breathing stays easy, my posture stays open, and I choose clearly under pressure.',cue:'my shoulders settle and my gaze softens',context:'conversation, training, performance, and pressure',induction:'eye',deepener:'stairs',style:'Identity installation',style2:'First-person affirmation',audio:'bilateral',visual:'fixation',beat:5,reps:9,ending:'Wake energized'},
 skill:{name:'Skill Chamber',emoji:'⚙',desc:'Mental reps + cue linking',goal:'My body recalls the chosen skill with sharper timing, cleaner mechanics, and less wasted motion.',evidence:'The first movement starts smoothly and the correct sequence follows with less conscious effort.',cue:'I visualize the first movement',context:'drills, sparring, performance, and learning',induction:'body',deepener:'time',style:'Mental rehearsal',style2:'Implementation intention',audio:'iso',visual:'tunnel',beat:7,reps:10,ending:'Wake energized'},
 sleep:{name:'Sleep Descent',emoji:'☾',desc:'Quiet integration + rest',goal:'I release the day, become physically quiet, and let useful changes integrate while I rest.',evidence:'Breathing becomes soft, muscles release, and attention stops chasing unfinished thoughts.',cue:'my head settles onto the pillow',context:'night and recovery',induction:'pmr',deepener:'autogenic',style:'Permissive Ericksonian',style2:'Sensory imagery',audio:'brown',visual:'dark',beat:2,reps:5,ending:'Sleep descent'},
 calm:{name:'Pressure Calm',emoji:'≈',desc:'Reset cue + regulation',goal:'Pressure makes me clearer, slower inside, and more precise outside.',evidence:'I breathe once, widen awareness, and choose the cleanest next action.',cue:'I feel pressure rise',context:'competition, conflict, performance, and decision making',induction:'sensory',deepener:'blank',style:'Implementation intention',style2:'Contrast frame',audio:'pink',visual:'candle',beat:8,reps:8,ending:'Wake energized'},
 habit:{name:'Habit Installer',emoji:'↻',desc:'Cue → action chaining',goal:'I begin the chosen useful habit with very little friction and keep going once started.',evidence:'The cue leads directly to the first tiny action instead of delay.',cue:'the scheduled moment or environmental cue appears',context:'daily routines and planned practice',induction:'instant',deepener:'fractionDeep',style:'Implementation intention',style2:'Layered repetition',audio:'monaural',visual:'pulseText',beat:10,reps:10,ending:'Wake energized'},
 creativity:{name:'Creative Flow',emoji:'✦',desc:'Absorption + idea generation',goal:'Ideas connect freely while I remain able to choose and develop the strongest ones.',evidence:'I generate options without freezing, then select and refine with clarity.',cue:'I open the project and take one deliberate breath',context:'writing, art, design, problem solving, and invention',induction:'confusion',deepener:'spiralDeep',style:'Question loop',style2:'Sensory imagery',audio:'layered',visual:'spiral',beat:7,reps:7,ending:'Stay relaxed'},
 custom:{name:'Blank Forge',emoji:'＋',desc:'Build from zero',goal:'I install the exact program I choose.',evidence:'I recognize the result clearly in action.',cue:'I use my chosen cue',context:'the situations I choose',induction:'breath',deepener:'stairs',style:'Direct command',style2:'Implementation intention',audio:'none',visual:'breathe',beat:6,reps:8,ending:'Wake energized'}
};
const METHODS=[
 ['Induction','Breath pacing','Uses breath rhythm as a stable attentional anchor.','Match exhale length, wording pace, and visual breathing animation. Pair the cue with the exhale when building a rapid-entry routine.'],
 ['Induction','Eye fixation','Narrows visual attention until blinking or eye closure becomes a transition signal.','Choose a small dot, candle, or pendulum. Slow the visual rather than increasing intensity.'],
 ['Induction','Progressive muscle relaxation','Moves attention through muscle groups while releasing unnecessary effort.','Use shorter scans for daytime sessions and longer scans for sleep-oriented sessions.'],
 ['Induction','Body scan absorption','Amplifies internal sensory detail and reduces competition from outside stimuli.','Emphasize pressure, warmth, position, and contact instead of only relaxation.'],
 ['Induction','Fractionation','Alternates lighter and deeper focus to make the contrast more noticeable.','Use two or three eye-open/eye-closed cycles, then attach the inward shift to a chosen cue.'],
 ['Induction','Rapid countdown','Creates a compact ritual from ordinary attention into focused rehearsal.','Keep the same numbers and cue across repeated sessions when training rapid entry.'],
 ['Induction','Pattern confusion','Uses temporary cognitive looseness so simple imagery becomes easier to follow.','Keep the wording playful and brief; finish with a clear simple instruction.'],
 ['Induction','5-4-3-2-1 sensory narrowing','Moves from broad sensory inventory to one selected intention.','Customize which senses dominate based on the user’s strongest imagery channel.'],
 ['Induction','Self-Elman style','Combines eye relaxation, body relaxation, and deepening tests.','Treat the “test” as imagination practice rather than a pass/fail challenge.'],
 ['Induction','Cue-linked rapid entry','Rehearses one physical or verbal cue as the start of inward focus.','Use a cue that is easy to reproduce and a separate cancellation/reset cue.'],
 ['Deepener','Staircase descent','Uses spatial descent and counting to create sequential deepening.','Change stairs to tunnel, ladder, elevator, cave, dojo, cockpit, or any scene with personal meaning.'],
 ['Deepener','Count deepening','Uses a predictable number sequence as a repetition scaffold.','Use fewer numbers for rapid sessions and longer intervals for slow sessions.'],
 ['Deepener','Time distortion','Expands subjective rehearsal space.','Use it before mental practice so each imagined repetition feels unhurried and detailed.'],
 ['Deepener','Warmth and heaviness','Uses imagined body sensations as deepening markers.','Swap warmth for coolness, floating, lightness, or grounded weight according to preference.'],
 ['Deepener','Sensory scene immersion','Builds a full inner environment.','Add distance, texture, movement, temperature, sound, and depth instead of relying on visual imagery alone.'],
 ['Deepener','Blank-space deepening','Focuses on gaps between words and breaths.','Useful for users who prefer minimal imagery; increase silence gaps rather than adding more words.'],
 ['Suggestion','Direct command','Uses concise explicit language for the desired response.','Keep one behavior per sentence and define the context where it should appear.'],
 ['Suggestion','Permissive language','Frames change as something the mind can discover rather than force.','Use “can,” “may,” “begin,” and “notice” when a softer style feels more natural.'],
 ['Suggestion','Implementation intention','Pairs a specific cue with a specific response in an if/when → then structure.','Make the cue concrete, observable, and tightly matched to the real situation.'],
 ['Suggestion','First-person affirmation','Uses self-spoken identity and action statements.','Write in present tense and include observable behavior, not only abstract traits.'],
 ['Suggestion','Mental rehearsal','Runs vivid simulated repetitions of the target behavior.','Include the lead-in cue, first movement, key decision point, and successful completion.'],
 ['Suggestion','Sensory imagery','Represents the result as sight, sound, body feeling, and environment.','Use whichever channels feel most vivid instead of forcing all senses equally.'],
 ['Suggestion','Identity installation','Links repeated behavior to a chosen self-concept.','Anchor identity to evidence: “I am the kind of person who does X when Y happens.”'],
 ['Suggestion','Question loop','Uses open questions to direct attention toward signs of change.','Ask questions whose answers naturally imply the desired behavior without demanding a specific feeling.'],
 ['Suggestion','Contrast frame','Makes the old pattern distant and the new one immediate.','Contrast posture, imagery distance, color, sound, and timing, then end on the desired state.'],
 ['Suggestion','Layered repetition','Repeats the same target through words, imagery, feeling, and action.','Change representation while keeping the core behavior consistent.'],
 ['Integration','Future pacing','Rehearses the next real situation after the core suggestions.','Use a specific near-future scene rather than a vague “someday.”'],
 ['Integration','Post-hypnotic cue','Associates a later cue with the rehearsed response.','Specify exactly when the cue applies and include a clear reset/cancellation cue.'],
 ['Integration','Compounding','Frames each successful real-world repetition as making the next one easier.','Tie compounding to evidence of success instead of demanding perfect performance.'],
 ['Integration','Reorientation','Returns attention to ordinary alertness at the end.','Use a wake-up count for daytime sessions or omit it for sleep sessions.'],
 ['Experimental','Ideomotor imagination','Invites tiny imagined or spontaneous movement as a feedback channel.','Keep it optional: imagine a finger becoming lighter or heavier, then treat any response as information rather than proof.'],
 ['Experimental','Symbolic archetype','Encodes the desired response into a personally meaningful symbol or character.','Choose a symbol that already evokes posture, emotion, movement, or values you want to rehearse.'],
 ['Experimental','Fractionated cue conditioning','Repeatedly enters and leaves focused attention using the same cue.','Keep cycles brief and consistent, then test the cue later in an ordinary relaxed state.'],
 ['Audio/Visual','Binaural beat bed','Plays slightly different tones to left and right channels.','Use headphones, low volume, and treat beat rate as an adjustable texture rather than a guaranteed state selector.'],
 ['Audio/Visual','Isochronic pulse','Amplitude-modulates a tone at a chosen pulse rate.','Keep the background quieter than narration and lower intensity if it competes with the words.'],
 ['Audio/Visual','Bilateral sweep','Moves a soft sound slowly between left and right.','Adjust sweep period rather than making the panning abrupt.'],
 ['Audio/Visual','Noise bed','Uses white, pink, or brown-style noise as a masking texture.','Choose the quietest color that reduces distraction without hiding the narration.'],
 ['Audio/Visual','Slow visual entrainment','Uses breathing orb, fixation, spiral, tunnel, pendulum, candle, or text pulse.','Tune speed and brightness for comfort; reduced-motion mode disables animation.']
];
